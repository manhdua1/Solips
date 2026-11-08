# main.py
import asyncio
import os
import secrets
from typing import Optional

import cv2
import numpy as np
from fastapi import FastAPI, File, HTTPException, Request, UploadFile
from fastapi.responses import JSONResponse

from face_engine import FaceEngine, QualityCheckError

MAX_IMAGE_BYTES = int(os.getenv("MAX_IMAGE_BYTES", str(2 * 1024 * 1024)))  # 2 MiB per image default
MAX_REQUEST_BYTES = int(os.getenv("MAX_REQUEST_BYTES", str(7 * 1024 * 1024)))
MAX_SIDE = int(os.getenv("MAX_IMAGE_SIDE", "4096"))
MAX_PIXELS = int(os.getenv("MAX_IMAGE_PIXELS", "8000000"))
ALLOWED_CONTENT_TYPES = {"image/jpeg", "image/png", "image/webp", "image/jpg"}

app = FastAPI(title="Solips Face Service")
engine = FaceEngine(lazy_load=os.getenv("FACE_ENGINE_LAZY", "true").lower() in ("true", "1"))

CONCURRENCY_LIMIT = int(os.getenv("FACE_CONCURRENCY_LIMIT", "4"))
inference_semaphore = asyncio.Semaphore(CONCURRENCY_LIMIT)


@app.middleware("http")
async def limit_request_size(request: Request, call_next):
    content_length = request.headers.get("content-length")
    if content_length:
        try:
            if int(content_length) > MAX_REQUEST_BYTES:
                return JSONResponse(
                    status_code=413,
                    content={
                        "detail": f"UPLOAD_LIMITS_EXCEEDED: Request payload exceeds maximum {MAX_REQUEST_BYTES} bytes limit"
                    },
                )
        except (ValueError, TypeError):
            pass
    return await call_next(request)


def verify_auth(request: Request, require_secret: bool = False):
    secret = os.getenv("FACE_SERVICE_SECRET", "").strip()
    if not secret:
        if require_secret:
            raise HTTPException(status_code=503, detail="CONFIG_UNAVAILABLE")
        # If no secret is configured, allow internal calls (e.g. dev/test)
        return

    # Check X-Internal-Service-Key header or Bearer authorization
    provided_key = request.headers.get("X-Internal-Service-Key")
    if not provided_key:
        auth_header = request.headers.get("Authorization", "")
        if auth_header.startswith("Bearer "):
            provided_key = auth_header[7:].strip()

    if not provided_key or not secrets.compare_digest(provided_key, secret):
        raise HTTPException(status_code=401, detail="UNAUTHORIZED")


async def read_and_validate_image(file: UploadFile) -> np.ndarray:
    raw_content_type = (file.content_type or "").lower().strip()
    content_type = raw_content_type.split(";")[0].strip()
    if content_type not in ALLOWED_CONTENT_TYPES:
        raise HTTPException(
            status_code=415,
            detail=f"UNSUPPORTED_MEDIA_TYPE: Expected one of {sorted(ALLOWED_CONTENT_TYPES)}, got {raw_content_type}",
        )

    chunk_size = 64 * 1024
    buffer = bytearray()

    while True:
        chunk = await file.read(chunk_size)
        if not chunk:
            break
        buffer.extend(chunk)
        if len(buffer) > MAX_IMAGE_BYTES:
            raise HTTPException(
                status_code=413,
                detail=f"UPLOAD_LIMITS_EXCEEDED: Image size exceeds {MAX_IMAGE_BYTES} bytes limit",
            )

    if len(buffer) == 0:
        raise HTTPException(status_code=415, detail="UNSUPPORTED_MEDIA_TYPE: Empty file")

    img = cv2.imdecode(np.frombuffer(buffer, np.uint8), cv2.IMREAD_COLOR)
    if img is None:
        raise HTTPException(status_code=415, detail="UNSUPPORTED_MEDIA_TYPE: Unable to decode image")

    h, w = img.shape[:2]
    if w > MAX_SIDE or h > MAX_SIDE or (w * h) > MAX_PIXELS:
        raise HTTPException(
            status_code=413,
            detail=f"UPLOAD_LIMITS_EXCEEDED: Dimensions {w}x{h} ({w*h} pixels) exceed maximum side {MAX_SIDE} or pixel limit {MAX_PIXELS}",
        )

    return img


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/detect")
async def detect(request: Request, file: UploadFile = File(...)):
    verify_auth(request)
    img = await read_and_validate_image(file)
    async with inference_semaphore:
        faces = engine.analyze(img)
    return {
        "count": len(faces),
        "faces": [
            {
                "bbox": f.bbox.tolist() if hasattr(f.bbox, "tolist") else list(f.bbox),
                "score": float(f.det_score) if hasattr(f, "det_score") else 1.0,
            }
            for f in faces
        ],
    }


@app.post("/embed")
async def embed(request: Request, file: UploadFile = File(...)):
    verify_auth(request)
    img = await read_and_validate_image(file)
    async with inference_semaphore:
        faces = engine.analyze(img)
    if not faces:
        raise HTTPException(status_code=422, detail="NO_FACE")
    if len(faces) > 1:
        raise HTTPException(status_code=422, detail="MULTIPLE_FACES")
    f = faces[0]

    raw_emb = getattr(f, "normed_embedding", None)
    if raw_emb is None:
        raw_emb = getattr(f, "embedding", None)
    if raw_emb is None:
        raise HTTPException(status_code=422, detail="Face object missing embedding")

    bbox_val = f.bbox.tolist() if hasattr(f.bbox, "tolist") else list(f.bbox)
    emb_val = raw_emb.tolist() if hasattr(raw_emb, "tolist") else list(raw_emb)

    return {
        "embedding": emb_val,
        "det_score": float(f.det_score) if hasattr(f, "det_score") else 1.0,
        "bbox": bbox_val,
    }


@app.post("/enrollment/embed")
async def enrollment_embed(request: Request, file: UploadFile = File(...)):
    verify_auth(request, require_secret=True)
    img = await read_and_validate_image(file)

    async with inference_semaphore:
        faces = engine.analyze(img)

    if not faces:
        raise HTTPException(status_code=422, detail="NO_FACE")
    if len(faces) > 1:
        raise HTTPException(status_code=422, detail="MULTIPLE_FACES")

    try:
        result = engine.evaluate_enrollment_quality(img, faces[0])
        return JSONResponse(status_code=200, content=result)
    except QualityCheckError as e:
        raise HTTPException(status_code=422, detail=e.code)
    except ValueError as e:
        raise HTTPException(status_code=422, detail=str(e))
