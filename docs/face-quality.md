# Face Service & Enrollment Quality Verification Boundary

## 1. Overview & Architecture

The `face-service` is an internal Python 3.11 FastAPI microservice responsible for face detection, quality verification, and feature embedding extraction for the Solips platform.

It uses InsightFace (`buffalo_l` model pack) with an ArcFace/ResNet50 backbone producing normalized 512-dimensional floating-point embeddings.

### Core Principles
- **Stateless & Ephemeral**: The face service does not persist images, embeddings, or employee data to disk or database.
- **Strict Pre-Enrollment Quality Gate**: Rejects sub-standard faces early (too small, blurry, dark, overexposed, or multiple faces) so only high-quality vectors are sent to `core-service` for storage in MySQL.
- **Internal Service Protection**: Authenticated via a shared secret header or bearer token.
- **Bounded Resources**: In-flight inference is bounded by an async semaphore; payload size and pixel resolution are strictly bounded to prevent DoS or memory exhaustion.

---

## 2. Service Authentication

Internal API calls from `core-service` to `face-service` are authenticated using a shared secret.

### Configuration
- Environment variable: `FACE_SERVICE_SECRET`
- If `FACE_SERVICE_SECRET` is set and non-empty, every call to `/detect`, `/embed`, and `/enrollment/embed` must provide either:
  1. Header `X-Internal-Service-Key: <secret>`
  2. Header `Authorization: Bearer <secret>`
- If the secret is omitted or mismatched:
  - HTTP `401 Unauthorized`
  - Response body: `{"detail": "UNAUTHORIZED"}`
- If `FACE_SERVICE_SECRET` is empty/unset, authentication is bypassed (for local development).

---

## 3. Input & Payload Limits

Requests sending images must use `multipart/form-data` with the file field named `file`.

| Constraint | Environment Variable | Default Value | Violation Response |
|---|---|---|---|
| Max File Bytes | `MAX_IMAGE_BYTES` | 2,097,152 (2 MiB) | HTTP 413 `UPLOAD_LIMITS_EXCEEDED: Image size exceeds ...` |
| Max Request Bytes | `MAX_REQUEST_BYTES` | 7,340,032 (7 MiB) | HTTP 413 `UPLOAD_LIMITS_EXCEEDED: ...` |
| Max Side Length | `MAX_IMAGE_SIDE` | 4096 px | HTTP 413 `UPLOAD_LIMITS_EXCEEDED: Dimensions ...` |
| Max Pixel Count | `MAX_IMAGE_PIXELS` | 8,000,000 px | HTTP 413 `UPLOAD_LIMITS_EXCEEDED: Dimensions ...` |
| Allowed MIME Types | Hardcoded allowlist | `image/jpeg`, `image/png`, `image/webp`, `image/jpg` | HTTP 415 `UNSUPPORTED_MEDIA_TYPE: Expected one of ...` |
| Image Decoding | N/A | Valid OpenCV decodable image | HTTP 415 `UNSUPPORTED_MEDIA_TYPE: Unable to decode image` |

---

## 4. Quality Boundary & Verification Rules

When calling `POST /enrollment/embed`, the image must pass all of the following checks in order:

### 1. Exactly One Face
- If 0 faces detected: HTTP 422 `{"detail": "NO_FACE"}`
- If > 1 faces detected: HTTP 422 `{"detail": "MULTIPLE_FACES"}`

### 2. Face Size (`FACE_TOO_SMALL`)
- Configured by:
  - `MIN_FACE_WIDTH` (default: `120` px)
  - `MIN_FACE_HEIGHT` (default: `120` px)
  - `MIN_FACE_AREA_RATIO` (default: `0.10` / 10% of total image area)
- Condition: `face_width < MIN_FACE_WIDTH or face_height < MIN_FACE_HEIGHT or face_area_ratio < MIN_FACE_AREA_RATIO`
- Failure: HTTP 422 `{"detail": "FACE_TOO_SMALL"}`

### 3. Face Blur / Sharpness (`IMAGE_BLURRY`)
- Crop: Face bounding box coordinates clamped to image dimensions.
- Metric: Laplacian variance on grayscale face crop:
  $$\sigma^2 = \text{Var}(\nabla^2 I_{\text{gray}})$$
- Configured by: `MIN_LAPLACIAN_VAR` (default: `100.0`)
- Condition: `laplacian_var < MIN_LAPLACIAN_VAR`
- Failure: HTTP 422 `{"detail": "IMAGE_BLURRY"}`

### 4. Illumination / Lighting (`ILLUMINATION_POOR`)
- Metric: Mean grayscale pixel intensity on face crop:
  $$\mu = \frac{1}{N} \sum I_{\text{gray}}$$
- Configured by:
  - `MIN_BRIGHTNESS` (default: `50.0`)
  - `MAX_BRIGHTNESS` (default: `205.0`)
- Condition: `mean_brightness < MIN_BRIGHTNESS or mean_brightness > MAX_BRIGHTNESS`
- Failure: HTTP 422 `{"detail": "ILLUMINATION_POOR"}`

### 5. Vector Integrity & Normalization
- Embedding must be finite (no NaN, null, or Inf).
- L2-normalized ($||\mathbf{v}||_2 = 1.0$).
- Dimension must equal 512.
- Failure: HTTP 422 `{"detail": "<error_message>"}`

---

## 5. API Endpoints Reference

### 5.1 Health Check
- **Path**: `GET /health`
- **Auth**: None
- **Response**: `200 OK`
```json
{
  "status": "ok"
}
```

### 5.2 Legacy Detection
- **Path**: `POST /detect`
- **Auth**: Service auth
- **Form Data**: `file` (binary image)
- **Response**: `200 OK`
```json
{
  "count": 1,
  "faces": [
    {
      "bbox": [50.0, 50.0, 250.0, 250.0],
      "score": 0.998
    }
  ]
}
```

### 5.3 Legacy Embed
- **Path**: `POST /embed`
- **Auth**: Service auth
- **Form Data**: `file` (binary image)
- **Errors**: `422 NO_FACE`, `422 MULTIPLE_FACES`
- **Response**: `200 OK`
```json
{
  "embedding": [0.012, -0.045, ...],
  "det_score": 0.998,
  "bbox": [50.0, 50.0, 250.0, 250.0]
}
```

### 5.4 Enrollment Embed (Primary Integration Endpoint)
- **Path**: `POST /enrollment/embed`
- **Auth**: Service auth
- **Form Data**: `file` (binary image)
- **Success Response**: `200 OK`
```json
{
  "embedding": [0.0123, -0.0456, ...],
  "dimension": 512,
  "modelId": "buffalo_l",
  "modelVersion": "4c06341c33c2ca1f86781dab0e829f88ad5b64be9fba56e56bc9ebdefc619e43",
  "preprocessingVersion": "enrollment-v1",
  "quality": {
    "detScore": 0.9985,
    "faceWidth": 200,
    "faceHeight": 200,
    "faceAreaRatio": 0.25,
    "laplacianVar": 182.4,
    "meanBrightness": 128.5
  }
}
```

---

## 6. Java `core-service` Error Mapping

When `core-service` sends enrollment images to `face-service` or receives requests on enrollment endpoints, it maps errors into frozen public `ErrorCode` numbers and HTTP statuses:

| Upstream / Operation Condition | Upstream `detail` / Cause | Target `ErrorCode` | Application Code | HTTP Status to Client | Default Message |
|---|---|---|---|---|---|
| Revision mismatch | Revision conflict / stale data | `STALE_REVISION` | 1008 | 409 CONFLICT | Face revision conflict; please refresh |
| Consent version mismatch | Consent policy version outdated | `INVALID_CONSENT_VERSION` | 1009 | 409 CONFLICT | Consent version mismatch or outdated |
| File/payload exceeds limit | File > 2MiB or request > 7MiB | `UPLOAD_TOO_LARGE` | 1010 | 413 PAYLOAD_TOO_LARGE | Image or request payload exceeds limit |
| Unsupported media type | Not JPEG/PNG or invalid magic bytes | `UNSUPPORTED_MEDIA_TYPE` | 1011 | 415 UNSUPPORTED_MEDIA_TYPE | Unsupported media type; expected JPEG or PNG |
| Face not detected | `NO_FACE` | `NO_FACE` | 1012 | 422 UNPROCESSABLE_ENTITY | No face detected in image |
| Multiple faces detected | `MULTIPLE_FACES` | `MULTIPLE_FACES` | 1013 | 422 UNPROCESSABLE_ENTITY | Multiple faces detected in image |
| Face too small/far | `FACE_TOO_SMALL` | `FACE_TOO_SMALL` | 1014 | 422 UNPROCESSABLE_ENTITY | Face is too small or too far away |
| Image is blurry | `IMAGE_BLURRY` | `IMAGE_BLURRY` | 1015 | 422 UNPROCESSABLE_ENTITY | Image is blurry |
| Illumination poor | `ILLUMINATION_POOR` | `ILLUMINATION_POOR` | 1016 | 422 UNPROCESSABLE_ENTITY | Lighting is too dark or overexposed |
| Consent receipt invalid/expired | Receipt invalid/expired/declined | `INVALID_CONSENT` | 1017 | 422 UNPROCESSABLE_ENTITY | Face consent is invalid, expired, or not accepted |
| Upstream service error | 502 / 500 / unexpected response | `UPSTREAM_FACE_SERVICE_ERROR` | 1018 | 502 BAD_GATEWAY | Face service upstream error |
| Service unavailable | Connection refused / unreachable | `FACE_SERVICE_UNAVAILABLE` | 1019 | 503 SERVICE_UNAVAILABLE | Face service is unavailable |
| Request timeout | Timeout communicating upstream | `FACE_SERVICE_TIMEOUT` | 1020 | 504 GATEWAY_TIMEOUT | Face service request timed out |
| Internal / unexpected error | Unhandled runtime exception | `UNCATEGORIZED_EXCEPTION` | 9999 | 500 INTERNAL_SERVER_ERROR | Internal server error |

---

## 7. Configuration Environment Variables

| Variable Name | Default | Purpose |
|---|---|---|
| `FACE_SERVICE_SECRET` | `""` | Shared secret key for internal service authentication |
| `FACE_CONCURRENCY_LIMIT` | `4` | Maximum concurrent CPU inference tasks via semaphore |
| `FACE_ENGINE_LAZY` | `true` | When true, delays model loading until first inference call |
| `MAX_IMAGE_BYTES` | `2097152` | Max uploaded image file size in bytes (2 MiB) |
| `MAX_REQUEST_BYTES` | `7340032` | Max request payload size in bytes (7 MiB) |
| `MAX_IMAGE_SIDE` | `4096` | Max image width or height in pixels |
| `MAX_IMAGE_PIXELS` | `8000000` | Max total image pixel count (width * height) |
| `MIN_FACE_WIDTH` | `120` | Minimum face crop width in pixels |
| `MIN_FACE_HEIGHT` | `120` | Minimum face crop height in pixels |
| `MIN_FACE_AREA_RATIO` | `0.10` | Minimum face crop area as fraction of image area |
| `MIN_LAPLACIAN_VAR` | `100.0` | Minimum Laplacian variance on face crop (sharpness) |
| `MIN_BRIGHTNESS` | `50.0` | Minimum mean grayscale pixel brightness (illumination) |
| `MAX_BRIGHTNESS` | `205.0` | Maximum mean grayscale pixel brightness (illumination) |

---

## 8. Unit Testing Without Model Weights

Unit tests run entirely offline with zero model downloads:
- `tests/test_face_engine.py`: Unit tests for bounding-box quality evaluation math, blur detection, brightness boundaries, size limits, and vector normalization.
- `tests/test_api.py`: FastAPI TestClient tests for authentication, media validation, size/resolution limits, error responses, and endpoint compatibility using synthetic images and a `FakeEngine`.
- Execution command:
  ```powershell
  $env:PYTHONPATH="face-service"; .\face-service\.venv\Scripts\python.exe -m unittest discover -s face-service/tests -p "test_*.py"
  ```
