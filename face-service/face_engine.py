# face_engine.py
import hashlib
import os
from pathlib import Path
from typing import Any, Dict, Optional, Tuple

import cv2
import numpy as np


class QualityCheckError(Exception):
    def __init__(self, code: str, message: str = ""):
        self.code = code
        self.message = message
        super().__init__(f"{code}: {message}" if message else code)


class FaceEngine:
    MODEL_ID = "buffalo_l"
    PREPROCESSING_VERSION = "enrollment-v1"
    DIMENSION = 512
    DEFAULT_RECOGNITION_SHA256 = (
        "4c06341c33c2ca1f86781dab0e829f88ad5b64be9fba56e56bc9ebdefc619e43"
    )

    def __init__(
        self,
        model: str = "buffalo_l",
        det_size: Tuple[int, int] = (640, 640),
        lazy_load: bool = True,
    ):
        self.model_name = model
        self.det_size = det_size
        self._model_version: Optional[str] = None
        self.app = None

        if not lazy_load and model is not None:
            self._load_app()

    def _load_app(self):
        from insightface.app import FaceAnalysis

        self.app = FaceAnalysis(
            name=self.model_name,
            providers=["CPUExecutionProvider"],
            allowed_modules=["detection", "recognition"],  # skip unused modules for speed
        )
        self.app.prepare(ctx_id=-1, det_size=self.det_size)

    def get_model_version(self) -> str:
        if self._model_version is not None:
            return self._model_version

        # Try to locate the w600k_r50.onnx model file
        possible_paths = [
            Path.home() / ".insightface" / "models" / self.model_name / "w600k_r50.onnx",
            Path(__file__).parent / "data" / "models" / "w600k_r50.onnx",
        ]
        for p in possible_paths:
            if p.exists() and p.is_file():
                try:
                    self._model_version = hashlib.sha256(p.read_bytes()).hexdigest()
                    return self._model_version
                except Exception:
                    pass

        # Fallback to known recognition artifact sha256
        self._model_version = self.DEFAULT_RECOGNITION_SHA256
        return self._model_version

    @staticmethod
    def decode(data: bytes) -> np.ndarray:
        img = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_COLOR)  # BGR
        if img is None:
            raise ValueError("invalid image")
        return img

    def analyze(self, img: np.ndarray):
        if self.app is None:
            self._load_app()
        return self.app.get(img)

    def evaluate_enrollment_quality(self, img: np.ndarray, face: Any) -> Dict[str, Any]:
        h_img, w_img = img.shape[:2]
        bbox = face.bbox
        x1, y1, x2, y2 = float(bbox[0]), float(bbox[1]), float(bbox[2]), float(bbox[3])
        x1, x2 = min(x1, x2), max(x1, x2)
        y1, y2 = min(y1, y2), max(y1, y2)

        face_w = max(0.0, x2 - x1)
        face_h = max(0.0, y2 - y1)
        face_area = face_w * face_h
        img_area = float(w_img * h_img)
        area_ratio = face_area / img_area if img_area > 0 else 0.0

        # Quality check: Face size (>=120x120 and >=10% image area)
        min_width = int(os.getenv("MIN_FACE_WIDTH", "120"))
        min_height = int(os.getenv("MIN_FACE_HEIGHT", "120"))
        min_area_ratio = float(os.getenv("MIN_FACE_AREA_RATIO", "0.10"))

        if face_w < min_width or face_h < min_height or area_ratio < min_area_ratio:
            raise QualityCheckError(
                "FACE_TOO_SMALL",
                f"Face crop {int(face_w)}x{int(face_h)} (area {area_ratio:.2%}) "
                f"below thresholds {min_width}x{min_height} and {min_area_ratio:.0%}",
            )

        # Crop face region for blur and illumination checks
        c_x1 = max(0, min(w_img - 1, int(round(x1))))
        c_y1 = max(0, min(h_img - 1, int(round(y1))))
        c_x2 = max(c_x1 + 1, min(w_img, int(round(x2))))
        c_y2 = max(c_y1 + 1, min(h_img, int(round(y2))))
        face_crop = img[c_y1:c_y2, c_x1:c_x2]

        if face_crop.size == 0 or face_crop.shape[0] == 0 or face_crop.shape[1] == 0:
            raise QualityCheckError("FACE_TOO_SMALL", "Empty face bounding crop")

        gray_crop = cv2.cvtColor(face_crop, cv2.COLOR_BGR2GRAY)

        # Quality check: Blur (Laplacian variance >= 100)
        laplacian_var = float(cv2.Laplacian(gray_crop, cv2.CV_64F).var())
        min_laplacian_var = float(os.getenv("MIN_LAPLACIAN_VAR", "100.0"))
        if laplacian_var < min_laplacian_var:
            raise QualityCheckError(
                "IMAGE_BLURRY",
                f"Laplacian variance {laplacian_var:.1f} below threshold {min_laplacian_var:.1f}",
            )

        # Quality check: Illumination (mean grayscale brightness 50..205)
        mean_brightness = float(np.mean(gray_crop))
        min_brightness = float(os.getenv("MIN_BRIGHTNESS", "50.0"))
        max_brightness = float(os.getenv("MAX_BRIGHTNESS", "205.0"))
        if mean_brightness < min_brightness or mean_brightness > max_brightness:
            raise QualityCheckError(
                "ILLUMINATION_POOR",
                f"Mean brightness {mean_brightness:.1f} outside range [{min_brightness:.1f}, {max_brightness:.1f}]",
            )

        # Vector normalization and finite check
        raw_emb = getattr(face, "normed_embedding", None)
        if raw_emb is None:
            raw_emb = getattr(face, "embedding", None)
        if raw_emb is None:
            raise ValueError("Face object missing embedding")

        emb = np.asarray(raw_emb, dtype=np.float32)
        if not np.all(np.isfinite(emb)):
            raise ValueError("Face embedding contains NaN or infinite values")

        norm = float(np.linalg.norm(emb))
        if norm > 0:
            emb = emb / norm
        else:
            raise ValueError("Zero-norm embedding vector")

        return {
            "embedding": emb.tolist(),
            "dimension": self.DIMENSION,
            "modelId": self.MODEL_ID,
            "modelVersion": self.get_model_version(),
            "preprocessingVersion": self.PREPROCESSING_VERSION,
            "quality": {
                "detScore": float(face.det_score) if hasattr(face, "det_score") else 1.0,
                "faceWidth": int(round(face_w)),
                "faceHeight": int(round(face_h)),
                "faceAreaRatio": round(area_ratio, 4),
                "laplacianVar": round(laplacian_var, 2),
                "meanBrightness": round(mean_brightness, 2),
            },
        }
