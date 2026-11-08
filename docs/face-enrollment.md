# Face Enrollment Specification & Operations

## 1. Overview & Storage Boundaries
Solips face enrollment processes 3 facial image samples per employee via `face-service`, extracts 512-dimensional embeddings, and records metadata in `core-service`.
- **No Durable Raw Storage**: Raw facial images are never stored in MySQL or durable storage; only 512-d float vector BLOBs and scalar quality metrics are persisted.
- **Upload Parser Disk Risk**: Standard multipart form parsers (Spring/Tomcat `MultipartFile` and FastAPI `UploadFile`) temporarily buffer chunks to OS temporary disk during request parsing.
- **No Liveness / Prototype Disclaimer**: No active/passive anti-spoofing or liveness detection is implemented; accepts static 2D images. Not certified for production or high-security authentication.

## 2. Environment Variables & Secret Separation
The consent token signing key and internal service auth key are strictly separated and must never be shared.

| Variable Name | Configuration Property | Default | Purpose |
|---|---|---|---|
| `FACE_SERVICE_URL` | `face.service.url` | `http://localhost:8001` | Upstream Python `face-service` endpoint URL |
| `FACE_SERVICE_SECRET` | `face.service.secret` | `""` | Internal auth header `X-Internal-Service-Key` / Bearer |
| `FACE_CONSENT_SECRET` | `face.consent.secret` | `""` | HS256 JWT signing secret for consent receipts (min 32 bytes) |
| `FACE_EXPECTED_MODEL_VERSION` | `face.service.expected-model-version` | `""` | Pinned SHA-256 hash of model artifact (`w600k_r50.onnx`) |
| `FACE_SERVICE_CONNECT_TIMEOUT_MILLIS` | `face.service.connect-timeout-millis` | `3000` | HTTP connect timeout (ms) |
| `FACE_SERVICE_READ_TIMEOUT_MILLIS` | `face.service.read-timeout-millis` | `15000` | HTTP read/inference timeout (ms) |

## 3. Model Specification
- **Model**: InsightFace `buffalo_l` (ArcFace / ResNet-50 backbone, 512-dim L2-normalized vector).
- **Service URL**: `http://localhost:8001` (`POST /enrollment/embed`).
- **Model SHA-256 Pin**: `4c06341c33c2ca1f86781dab0e829f88ad5b64be9fba56e56bc9ebdefc619e43`.

## 4. MySQL Manual Migration Prerequisites (No Execute)
Apply manually to MySQL; do not rely on Hibernate `ddl-auto: update`.

```sql
-- 1. Add revision column and backfill existing employees
ALTER TABLE employees ADD COLUMN face_revision BIGINT NOT NULL DEFAULT 0;
UPDATE employees SET face_revision = 0 WHERE face_revision IS NULL;

-- 2. Create enrollment table
CREATE TABLE employee_face_enrollments (
    id BINARY(16) PRIMARY KEY,
    employee_id BINARY(16) NOT NULL UNIQUE,
    enrolled_at DATETIME(6) NOT NULL,
    consented_at DATETIME(6) NOT NULL,
    consented_by_admin_id BINARY(16) NOT NULL,
    consent_version VARCHAR(64) NOT NULL,
    consent_text TEXT NOT NULL,
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

## 5. Verification Status & Test Suite
- **Automated Tests**: Backend 79 tests passing, Frontend 75 tests passing, Python 24 tests passing earlier.
- **Unverified Real Integrations**: Live browser webcam feed capture / UX and real MySQL persistence / concurrency remain unverified against live instances.
