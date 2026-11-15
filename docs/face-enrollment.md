# Face Enrollment Specification & Operations

## 1. Overview & Storage Boundaries
Solips face enrollment processes 3 facial image samples per employee via `face-service`, extracts 512-dimensional embeddings, and records metadata in `core-service`.
- **No Durable Raw Storage**: Raw facial images are never stored in MySQL or durable storage; only 512-d float vector BLOBs and scalar quality metrics are persisted.
- **Upload Parser Disk Risk**: Standard multipart form parsers (Spring/Tomcat `MultipartFile` and FastAPI `UploadFile`) temporarily buffer chunks to OS temporary disk during request parsing.
- **No Liveness / Prototype Disclaimer**: No active/passive anti-spoofing or liveness detection is implemented; accepts static 2D images. Not certified for production or high-security authentication.

## 2. Environment Variables & Secret Separation
The internal service auth key protects communication between `core-service` and `face-service`. Both services share `FACE_SERVICE_SECRET` via the git-ignored repository-root `.env` file (loaded automatically by Spring Boot configuration imports and `face-service/config.py`).
*Note: Any updates to root `.env` require an IDE/process restart of `core-service` for Spring to refresh its environment configuration.*

| Variable Name | Configuration Property | Default | Purpose |
|---|---|---|---|
| `FACE_SERVICE_URL` | `face.service.url` | `http://localhost:8001` | Upstream Python `face-service` endpoint URL |
| `FACE_SERVICE_SECRET` | `face.service.secret` | `""` | Internal auth header `X-Internal-Service-Key` / Bearer |
| `FACE_EXPECTED_MODEL_VERSION` | `face.service.expected-model-version` | `""` | Pinned SHA-256 hash of model artifact (`w600k_r50.onnx`) |
| `FACE_SERVICE_CONNECT_TIMEOUT_MILLIS` | `face.service.connect-timeout-millis` | `3000` | HTTP connect timeout (ms) |
| `FACE_SERVICE_READ_TIMEOUT_MILLIS` | `face.service.read-timeout-millis` | `15000` | HTTP read/inference timeout (ms) |

### 2.1 Local Quality Threshold Testing & Precedence
For local webcam testing, relaxed quality thresholds can be configured in root `.env` (`MIN_FACE_WIDTH=60`, `MIN_FACE_HEIGHT=60`, `MIN_FACE_AREA_RATIO=0.03`, `MIN_LAPLACIAN_VAR=20`, `MIN_BRIGHTNESS=25`, `MAX_BRIGHTNESS=235`).
- **Reversion**: Remove or reset to code defaults (`120`, `120`, `0.10`, `100.0`, `50.0`, `205.0`) for production.
- **Precedence**: OS environment overrides `.env` file (`override=False`). If set in coordinator process environment, clear the six threshold variables to allow the configured `.env` file to take effect.
- **Biometric Risks**: Relaxed thresholds allow small, blurry, or poor-illumination images to pass quality gates, significantly increasing False Acceptance and False Rejection Rates during recognition matching. See `docs/face-quality.md` Section 7.1.

## 3. Model Specification
- **Model**: InsightFace `buffalo_l` (ArcFace / ResNet-50 backbone, 512-dim L2-normalized vector).
- **Service URLs**:
  - `POST /enrollment/quality`: Detection-only probe for transient guided quality evaluation without embedding extraction.
  - `POST /enrollment/embed`: Embedding extraction for accepted final enrollment images.
- **Model SHA-256 Pin**: `4c06341c33c2ca1f86781dab0e829f88ad5b64be9fba56e56bc9ebdefc619e43`.

## 4. Guided Auto Enrollment Workflow
1. **Periodic Transient Quality Probes**: During camera preview, client periodically posts transient webcam frames to `POST /api/employees/{id}/face/quality`.
   - Admin bearer authorization is enforced.
   - Core service validates employee exists and is not deleted.
   - Forwards image with explicit MIME to Python `POST /enrollment/quality`.
   - Python executes detection-only (no ArcFace embedding forward pass) and evaluates identical quality thresholds (one face, size, blur, illumination).
   - Core service validates finite positive geometry and returns normalized bounding box and metrics with `Cache-Control: no-store`.
   - Probes never hold transactions, never alter revisions, and never persist images or vectors.
2. **Explicit Final Save**: After 3 satisfactory frames are captured by the client:
   - Client sends `PUT /api/employees/{id}/face` with exactly 3 multipart images (`images`) and `expectedRevision`.
   - Core service invokes `POST /enrollment/embed` on all 3 images, re-validates quality metrics, extracts 512-dim embeddings, checks vector normalization, and commits atomically in a single transaction with optimistic revision increment.

## 4. MySQL Manual Migration Prerequisites (No Execute)
Apply manually to MySQL; do not rely on Hibernate `ddl-auto: update`.

### 4.1 Fresh Schema Setup (Consent-Free)
```sql
-- 1. Add revision column and backfill existing employees
ALTER TABLE employees ADD COLUMN face_revision BIGINT NOT NULL DEFAULT 0;
UPDATE employees SET face_revision = 0 WHERE face_revision IS NULL;

-- 2. Create enrollment table
CREATE TABLE employee_face_enrollments (
    id BINARY(16) PRIMARY KEY,
    employee_id BINARY(16) NOT NULL UNIQUE,
    enrolled_at DATETIME(6) NOT NULL,
    enrolled_by_admin_id BINARY(16) NULL,
    model_id VARCHAR(64) NOT NULL,
    model_version VARCHAR(128) NOT NULL,
    preprocessing_version VARCHAR(64) NOT NULL,
    sample_count INT NOT NULL DEFAULT 3,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_enrollment_employee FOREIGN KEY (employee_id) REFERENCES employees(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3. Create sample vectors table
CREATE TABLE employee_face_samples (
    id BINARY(16) PRIMARY KEY,
    enrollment_id BINARY(16) NOT NULL,
    slot INT NOT NULL,
    embedding BLOB NOT NULL,
    dimension INT NOT NULL DEFAULT 512,
    model_id VARCHAR(64) NOT NULL,
    model_version VARCHAR(128) NOT NULL,
    preprocessing_version VARCHAR(64) NOT NULL,
    det_score FLOAT NULL,
    face_width INT NULL,
    face_height INT NULL,
    face_area_ratio FLOAT NULL,
    laplacian_var FLOAT NULL,
    mean_brightness FLOAT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_sample_enrollment FOREIGN KEY (enrollment_id) REFERENCES employee_face_enrollments(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 4.2 Compatibility Migration for Existing Deployments (Preserving Historical Data & Samples)
To upgrade existing databases that already have legacy consent columns, preserve existing samples and historical consent data in-place without dropping records.
*User approval is strictly required before executing any DDL in production or unmigrated environments.*

**Root Cause Note (HTTP 409 Generic Conflict)**:
When consent was removed from `EmployeeFaceEnrollment`, legacy columns were unmapped in JPA. If MySQL still enforces `NOT NULL` on legacy consent columns, inserts fail with MySQL error 1364 (`Field '...' doesn't have a default value`), translated by Spring to `DataIntegrityViolationException`. `GlobalExceptionHandler` maps this to `DUPLICATE_RESOURCE` (1006, HTTP 409 Conflict), which triggers the frontend generic 409 error message (`'Dữ liệu bị trùng hoặc vừa thay đổi. Hãy kiểm tra và thử lại.'`). Making legacy columns nullable resolves this conflict.

```sql
-- 1. Make legacy consent columns nullable (preserves historical consent data in-place)
ALTER TABLE employee_face_enrollments
    MODIFY COLUMN consented_at DATETIME(6) NULL,
    MODIFY COLUMN consented_by_admin_id BINARY(16) NULL,
    MODIFY COLUMN consent_version VARCHAR(64) NULL,
    MODIFY COLUMN consent_text TEXT NULL;

-- 2. Add actual enrolling admin actor column if not already present
ALTER TABLE employee_face_enrollments
    ADD COLUMN enrolled_by_admin_id BINARY(16) NULL;

-- IMPORTANT: Do NOT backfill enrolled_by_admin_id from consented_by_admin_id.
-- Approval explicitly prohibited reinterpreting consent actor as enrollment actor.
-- Historical rows truthfully retain NULL for enrolled_by_admin_id.
```

## 5. Verification Status & Test Suite
- **Automated Tests**: Backend 114 tests passing (including 15 `KioskRecognitionServiceTest`, 12 `KioskControllerSecurityMvcTest`, 3 `RepositoryContractTest`, 16 `FaceEnrollmentSecurityMvcTest`, 5 `FaceConfigurationTest`), Frontend 75 tests passing, Python 24 tests passing earlier.
- **Modular Client Dependency**: Spring Boot 4 requires `spring-boot-starter-restclient` to auto-configure `RestClient.Builder` injected into `FaceConfiguration` and `FaceServiceClient`.
- **Unverified Real Integrations**: Live browser webcam feed capture / UX and real MySQL persistence / concurrency remain unverified against live instances.

## 6. Kiosk Face Recognition Demo (Specification & Limitations)

### 6.1 Public Endpoint Specification
- **Endpoint**: `POST /api/kiosk/recognize`
- **Authentication**: Public (no token required). Security strictly permits ONLY the exact `POST /api/kiosk/recognize` route; all other kiosk paths/methods and all employee enrollment management endpoints remain strictly protected by JWT Admin authentication.
- **Header**: `Cache-Control: no-store` on all responses.
- **Request Format**: `multipart/form-data` with **exactly 1** file part named `image`. Extraneous files, unexpected part names, or form fields are rejected with HTTP 400 (`INVALID_REQUEST`).
- **Response Format**: `ApiResponse` with `code: 1000` and `result` payload containing **only** `{ "success": boolean, "reason": string }`.

```json
{
  "code": 1000,
  "result": {
    "success": true,
    "reason": "MATCHED"
  }
}
```

### 6.2 Decision Logic & Reason Codes
All biometric evaluation outcomes return HTTP 200 OK:
- `MATCHED`: (`success: true`) Top-1 candidate employee score $\ge 0.50$ (inclusive) and distinct Top-2 margin $(S_1 - S_2) \ge 0.08$ (inclusive), or single enrolled employee $\ge 0.50$.
- `NO_FACE`: (`success: false`) Upstream detector found zero faces.
- `MULTIPLE_FACES`: (`success: false`) Upstream detector found more than one face.
- `FACE_TOO_SMALL`: (`success: false`) Detected face does not meet bounding box or area thresholds.
- `IMAGE_BLURRY`: (`success: false`) Face Laplacian variance below blur threshold.
- `ILLUMINATION_POOR`: (`success: false`) Frame illumination too dark or overexposed.
- `NO_ENROLLED_SAMPLES`: (`success: false`) No active/non-deleted employees enrolled or all candidate samples incompatible/corrupt.
- `UNKNOWN`: (`success: false`) Top-1 employee similarity is below `minScore` ($< 0.50$).
- `AMBIGUOUS`: (`success: false`) Top-1 similarity $\ge 0.50$ but margin between distinct Top-1 and Top-2 employees is insufficient ($< 0.08$) or tied.

Non-biometric media and infrastructure errors retain standard HTTP error statuses (e.g., HTTP 400 for malformed multipart, HTTP 413 for payload $> 2\text{ MiB}$, HTTP 415 for non-image MIME, HTTP 502/503/504 for upstream timeouts/failures) with sanitized `ApiResponse.error(errorCode)`.

### 6.3 Matching Pipeline & Candidate Filtering
1. **Out-of-Transaction Upstream Call**: `FaceServiceClient.embedEnrollmentImage` runs without a database transaction or lock.
2. **Scalar Projection Query**: A read-only query joins `EmployeeFaceSample` to `EmployeeFaceEnrollment` and `Employee`, filtering `status = ACTIVE` and `deletedAt IS NULL`, returning scalar fields without full entity hydration or lazy loading.
3. **Compatibility & Corruption Gates**: Each stored candidate sample vector is checked before comparison:
   - Byte length must match exactly $\text{dimension} \times 4$ (little-endian float32).
   - Sample `modelId`, `modelVersion`, `preprocessingVersion`, and `dimension` must match the live probe metadata.
   - All floats must be finite (no `NaN` or $\pm\infty$) and vector must be nonzero.
   - Any corrupt or mismatched sample is silently skipped (no database mutations).
4. **Cosine Calculation & Aggregation**:
   - Double precision cosine similarity: $\frac{u \cdot v}{\|u\|_2 \|v\|_2}$.
   - Multiple samples for the same employee are aggregated using $\max$ score.
   - Distinct employees are ranked to identify Top-1 and Top-2 scores.

### 6.4 Privacy & Demo Limitations Disclaimer
- **Zero Sensitive Data Exposure**: Employee IDs, employee names, similarity scores, and embedding vectors are never returned in response bodies or emitted to application logs.
- **No Attendance Persistence**: This is an evaluation demo only. No attendance records, check-in history, or timestamps are recorded in the database.
- **No Liveness Detection / Anti-Spoofing**: The system performs 2D static frame matching only. It does not provide presentation attack detection (PAD), depth sensing, or blink analysis; it is vulnerable to photo or screen replay attacks.
- **Local-Only Auth Risk**: The endpoint has no caller authentication to support local kiosk webcam demos. In production, this requires hardware kiosk authorization (mTLS, device certificates, or restricted network segregation).
- **Uncalibrated Thresholds**: Defaults (`minScore = 0.50`, `minMargin = 0.08`, configurable via `face.recognition.min-score` and `face.recognition.min-margin`) are operational defaults and have not been calibrated on target enterprise population distributions.
