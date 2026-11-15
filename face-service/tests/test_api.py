import io
import os
import unittest
from unittest.mock import patch

import cv2
import numpy as np
from fastapi.testclient import TestClient

import main
from face_engine import FaceEngine, QualityCheckError
from tests.test_face_engine import DummyFace, create_test_image


class FakeEngine:
    def __init__(self, faces=None):
        self.faces = faces if faces is not None else []
        self.real_engine = FaceEngine(lazy_load=True)
        self.MODEL_ID = self.real_engine.MODEL_ID
        self.DIMENSION = self.real_engine.DIMENSION
        self.PREPROCESSING_VERSION = self.real_engine.PREPROCESSING_VERSION

    def get_model_version(self):
        return self.real_engine.get_model_version()

    def analyze(self, img):
        return self.faces

    def detect(self, img):
        return self.faces

    def evaluate_quality(self, img, face):
        return self.real_engine.evaluate_quality(img, face)

    def evaluate_enrollment_quality(self, img, face):
        return self.real_engine.evaluate_enrollment_quality(img, face)


def encode_image(img: np.ndarray, ext: str = ".jpg") -> bytes:
    success, buffer = cv2.imencode(ext, img)
    if not success:
        raise ValueError(f"Failed to encode image to {ext}")
    return buffer.tobytes()


class TestFaceServiceAPI(unittest.TestCase):
    AUTH_SECRET = "solips-top-secret"
    AUTH_HEADERS = {"X-Internal-Service-Key": "solips-top-secret"}
    THRESHOLD_KEYS = (
        "MIN_FACE_WIDTH",
        "MIN_FACE_HEIGHT",
        "MIN_FACE_AREA_RATIO",
        "MIN_LAPLACIAN_VAR",
        "MIN_BRIGHTNESS",
        "MAX_BRIGHTNESS",
    )

    def setUp(self):
        self.original_engine = main.engine
        self.fake_engine = FakeEngine()
        main.engine = self.fake_engine
        self.env_patch = patch.dict(os.environ, {"FACE_SERVICE_SECRET": ""})
        self.env_patch.start()
        self.orig_thresholds = {k: os.environ.pop(k) for k in self.THRESHOLD_KEYS if k in os.environ}
        self.client = TestClient(main.app)

    def tearDown(self):
        self.env_patch.stop()
        os.environ.update(self.orig_thresholds)
        main.engine = self.original_engine

    def test_health_check(self):
        response = self.client.get("/health")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"status": "ok"})

    def test_service_auth_when_secret_configured(self):
        img_bytes = encode_image(create_test_image())

        # When FACE_SERVICE_SECRET is empty/unset:
        # /detect and /embed allow legacy internal calls
        resp_detect_unauth = self.client.post(
            "/detect",
            files={"file": ("test.jpg", img_bytes, "image/jpeg")},
        )
        self.assertEqual(resp_detect_unauth.status_code, 200)

        # /enrollment/embed fails closed with 503 CONFIG_UNAVAILABLE
        resp_enroll_empty = self.client.post(
            "/enrollment/embed",
            files={"file": ("test.jpg", img_bytes, "image/jpeg")},
        )
        self.assertEqual(resp_enroll_empty.status_code, 503)
        self.assertEqual(resp_enroll_empty.json()["detail"], "CONFIG_UNAVAILABLE")

        # When FACE_SERVICE_SECRET is configured:
        with patch.dict(os.environ, {"FACE_SERVICE_SECRET": "solips-top-secret"}):
            # Missing auth header -> 401
            resp1 = self.client.post(
                "/detect",
                files={"file": ("test.jpg", img_bytes, "image/jpeg")},
            )
            self.assertEqual(resp1.status_code, 401)
            self.assertEqual(resp1.json()["detail"], "UNAUTHORIZED")

            # Mismatched auth header -> 401
            resp2 = self.client.post(
                "/detect",
                headers={"X-Internal-Service-Key": "wrong-secret"},
                files={"file": ("test.jpg", img_bytes, "image/jpeg")},
            )
            self.assertEqual(resp2.status_code, 401)
            self.assertEqual(resp2.json()["detail"], "UNAUTHORIZED")

            # Valid X-Internal-Service-Key -> 200
            resp3 = self.client.post(
                "/detect",
                headers={"X-Internal-Service-Key": "solips-top-secret"},
                files={"file": ("test.jpg", img_bytes, "image/jpeg")},
            )
            self.assertEqual(resp3.status_code, 200)

            # Valid Bearer Authorization -> 200
            resp4 = self.client.post(
                "/detect",
                headers={"Authorization": "Bearer solips-top-secret"},
                files={"file": ("test.jpg", img_bytes, "image/jpeg")},
            )
            self.assertEqual(resp4.status_code, 200)

            # Enrollment endpoint missing header -> 401
            resp5 = self.client.post(
                "/enrollment/embed",
                files={"file": ("test.jpg", img_bytes, "image/jpeg")},
            )
            self.assertEqual(resp5.status_code, 401)
            self.assertEqual(resp5.json()["detail"], "UNAUTHORIZED")

    def test_unsupported_media_type(self):
        resp = self.client.post(
            "/detect",
            files={"file": ("test.gif", b"GIF89a...", "image/gif")},
        )
        self.assertEqual(resp.status_code, 415)
        self.assertIn("UNSUPPORTED_MEDIA_TYPE", resp.json()["detail"])

    def test_empty_image_upload(self):
        resp = self.client.post(
            "/detect",
            files={"file": ("empty.jpg", b"", "image/jpeg")},
        )
        self.assertEqual(resp.status_code, 415)
        self.assertIn("Empty file", resp.json()["detail"])

    def test_corrupt_image_upload(self):
        resp = self.client.post(
            "/detect",
            files={"file": ("corrupt.jpg", b"not-a-valid-jpeg-stream", "image/jpeg")},
        )
        self.assertEqual(resp.status_code, 415)
        self.assertIn("Unable to decode image", resp.json()["detail"])

    def test_image_size_exceeds_max_bytes(self):
        # Limit bytes to 1000 bytes for testing
        with patch.object(main, "MAX_IMAGE_BYTES", 1000):
            img_bytes = encode_image(create_test_image())
            self.assertGreater(len(img_bytes), 1000)

            resp = self.client.post(
                "/detect",
                files={"file": ("test.jpg", img_bytes, "image/jpeg")},
            )
            self.assertEqual(resp.status_code, 413)
            self.assertIn("UPLOAD_LIMITS_EXCEEDED", resp.json()["detail"])

        # Also test MAX_REQUEST_BYTES middleware header guard
        with patch.object(main, "MAX_REQUEST_BYTES", 500):
            resp_req = self.client.post(
                "/detect",
                headers={"content-length": "600"},
                files={"file": ("test.jpg", b"small", "image/jpeg")},
            )
            self.assertEqual(resp_req.status_code, 413)
            self.assertIn("UPLOAD_LIMITS_EXCEEDED", resp_req.json()["detail"])

    def test_image_dimensions_exceed_limits(self):
        # Limit side to 200px for testing
        with patch.object(main, "MAX_SIDE", 200):
            img_bytes = encode_image(create_test_image(width=400, height=400))
            resp = self.client.post(
                "/detect",
                files={"file": ("large.jpg", img_bytes, "image/jpeg")},
            )
            self.assertEqual(resp.status_code, 413)
            self.assertIn("UPLOAD_LIMITS_EXCEEDED", resp.json()["detail"])

    def test_detect_endpoint_compatibility(self):
        self.fake_engine.faces = [
            DummyFace(bbox=(50, 50, 250, 250), det_score=0.99),
            DummyFace(bbox=(260, 50, 360, 250), det_score=0.95),
        ]
        img_bytes = encode_image(create_test_image())

        resp = self.client.post(
            "/detect",
            files={"file": ("detect.jpg", img_bytes, "image/jpeg")},
        )
        self.assertEqual(resp.status_code, 200)
        data = resp.json()
        self.assertEqual(data["count"], 2)
        self.assertEqual(len(data["faces"]), 2)
        self.assertEqual(data["faces"][0]["bbox"], [50.0, 50.0, 250.0, 250.0])
        self.assertAlmostEqual(data["faces"][0]["score"], 0.99, places=2)

    def test_embed_endpoint_compatibility(self):
        img_bytes = encode_image(create_test_image())

        # 0 faces
        self.fake_engine.faces = []
        resp0 = self.client.post(
            "/embed",
            files={"file": ("test.jpg", img_bytes, "image/jpeg")},
        )
        self.assertEqual(resp0.status_code, 422)
        self.assertEqual(resp0.json()["detail"], "NO_FACE")

        # 2 faces
        self.fake_engine.faces = [
            DummyFace(bbox=(10, 10, 50, 50)),
            DummyFace(bbox=(60, 60, 100, 100)),
        ]
        resp2 = self.client.post(
            "/embed",
            files={"file": ("test.jpg", img_bytes, "image/jpeg")},
        )
        self.assertEqual(resp2.status_code, 422)
        self.assertEqual(resp2.json()["detail"], "MULTIPLE_FACES")

        # 1 face
        self.fake_engine.faces = [DummyFace(bbox=(50, 50, 250, 250), det_score=0.98)]
        resp1 = self.client.post(
            "/embed",
            files={"file": ("test.jpg", img_bytes, "image/jpeg")},
        )
        self.assertEqual(resp1.status_code, 200)
        data = resp1.json()
        self.assertEqual(len(data["embedding"]), 512)
        self.assertEqual(data["bbox"], [50.0, 50.0, 250.0, 250.0])
        self.assertAlmostEqual(data["det_score"], 0.98, places=2)

    def test_enrollment_embed_no_face(self):
        self.fake_engine.faces = []
        img_bytes = encode_image(create_test_image())

        with patch.dict(os.environ, {"FACE_SERVICE_SECRET": self.AUTH_SECRET}):
            resp = self.client.post(
                "/enrollment/embed",
                headers=self.AUTH_HEADERS,
                files={"file": ("test.jpg", img_bytes, "image/jpeg")},
            )
            self.assertEqual(resp.status_code, 422)
            self.assertEqual(resp.json()["detail"], "NO_FACE")

    def test_enrollment_embed_multiple_faces(self):
        self.fake_engine.faces = [
            DummyFace(bbox=(10, 10, 50, 50)),
            DummyFace(bbox=(60, 60, 100, 100)),
        ]
        img_bytes = encode_image(create_test_image())

        with patch.dict(os.environ, {"FACE_SERVICE_SECRET": self.AUTH_SECRET}):
            resp = self.client.post(
                "/enrollment/embed",
                headers=self.AUTH_HEADERS,
                files={"file": ("test.jpg", img_bytes, "image/jpeg")},
            )
            self.assertEqual(resp.status_code, 422)
            self.assertEqual(resp.json()["detail"], "MULTIPLE_FACES")

    def test_enrollment_embed_face_too_small(self):
        # Face box 80x80 < 120x120
        self.fake_engine.faces = [DummyFace(bbox=(50, 50, 130, 130))]
        img_bytes = encode_image(create_test_image(face_box=(50, 50, 130, 130)))

        with patch.dict(os.environ, {"FACE_SERVICE_SECRET": self.AUTH_SECRET}):
            resp = self.client.post(
                "/enrollment/embed",
                headers=self.AUTH_HEADERS,
                files={"file": ("test.jpg", img_bytes, "image/jpeg")},
            )
            self.assertEqual(resp.status_code, 422)
            self.assertEqual(resp.json()["detail"], "FACE_TOO_SMALL")

    def test_enrollment_embed_image_blurry(self):
        self.fake_engine.faces = [DummyFace(bbox=(50, 50, 250, 250))]
        img_bytes = encode_image(create_test_image(face_type="blurry"))

        with patch.dict(os.environ, {"FACE_SERVICE_SECRET": self.AUTH_SECRET}):
            resp = self.client.post(
                "/enrollment/embed",
                headers=self.AUTH_HEADERS,
                files={"file": ("test.jpg", img_bytes, "image/jpeg")},
            )
            self.assertEqual(resp.status_code, 422)
            self.assertEqual(resp.json()["detail"], "IMAGE_BLURRY")

    def test_enrollment_embed_illumination_poor_dark(self):
        self.fake_engine.faces = [DummyFace(bbox=(50, 50, 250, 250))]
        img_bytes = encode_image(create_test_image(face_type="dark"))

        with patch.dict(os.environ, {"FACE_SERVICE_SECRET": self.AUTH_SECRET}):
            resp = self.client.post(
                "/enrollment/embed",
                headers=self.AUTH_HEADERS,
                files={"file": ("test.jpg", img_bytes, "image/jpeg")},
            )
            self.assertEqual(resp.status_code, 422)
            self.assertEqual(resp.json()["detail"], "ILLUMINATION_POOR")

    def test_enrollment_embed_illumination_poor_bright(self):
        self.fake_engine.faces = [DummyFace(bbox=(50, 50, 250, 250))]
        img_bytes = encode_image(create_test_image(face_type="bright"))

        with patch.dict(os.environ, {"FACE_SERVICE_SECRET": self.AUTH_SECRET}):
            resp = self.client.post(
                "/enrollment/embed",
                headers=self.AUTH_HEADERS,
                files={"file": ("test.jpg", img_bytes, "image/jpeg")},
            )
            self.assertEqual(resp.status_code, 422)
            self.assertEqual(resp.json()["detail"], "ILLUMINATION_POOR")

    def test_enrollment_embed_success(self):
        self.fake_engine.faces = [DummyFace(bbox=(50, 50, 250, 250), det_score=0.995)]
        img_bytes = encode_image(create_test_image(face_type="valid"))

        with patch.dict(os.environ, {"FACE_SERVICE_SECRET": self.AUTH_SECRET}):
            resp = self.client.post(
                "/enrollment/embed",
                headers=self.AUTH_HEADERS,
                files={"file": ("test.jpg", img_bytes, "image/jpeg")},
            )
            self.assertEqual(resp.status_code, 200)
            data = resp.json()

        self.assertEqual(data["modelId"], "buffalo_l")
        self.assertEqual(data["dimension"], 512)
        self.assertEqual(data["preprocessingVersion"], "enrollment-v1")
        self.assertIn("modelVersion", data)
        self.assertEqual(len(data["embedding"]), 512)

        emb = np.array(data["embedding"], dtype=np.float32)
        self.assertAlmostEqual(float(np.linalg.norm(emb)), 1.0, places=4)

        quality = data["quality"]
        self.assertAlmostEqual(quality["detScore"], 0.995, places=3)
        self.assertEqual(quality["faceWidth"], 200)
        self.assertEqual(quality["faceHeight"], 200)
        self.assertGreaterEqual(quality["laplacianVar"], 100.0)
        self.assertGreaterEqual(quality["meanBrightness"], 50.0)
        self.assertLessEqual(quality["meanBrightness"], 205.0)

    def test_enrollment_quality_requires_auth(self):
        img_bytes = encode_image(create_test_image())

        # Unconfigured secret -> 503
        resp_empty = self.client.post(
            "/enrollment/quality",
            files={"file": ("test.jpg", img_bytes, "image/jpeg")},
        )
        self.assertEqual(resp_empty.status_code, 503)

        with patch.dict(os.environ, {"FACE_SERVICE_SECRET": self.AUTH_SECRET}):
            # Missing header -> 401
            resp_no_auth = self.client.post(
                "/enrollment/quality",
                files={"file": ("test.jpg", img_bytes, "image/jpeg")},
            )
            self.assertEqual(resp_no_auth.status_code, 401)

            # Valid header -> passes auth check (testing with no faces -> 422 NO_FACE)
            self.fake_engine.faces = []
            resp_authed = self.client.post(
                "/enrollment/quality",
                headers=self.AUTH_HEADERS,
                files={"file": ("test.jpg", img_bytes, "image/jpeg")},
            )
            self.assertEqual(resp_authed.status_code, 422)
            self.assertEqual(resp_authed.json()["detail"], "NO_FACE")

    def test_enrollment_quality_errors(self):
        img_bytes = encode_image(create_test_image())

        with patch.dict(os.environ, {"FACE_SERVICE_SECRET": self.AUTH_SECRET}):
            # Multiple faces -> 422
            self.fake_engine.faces = [
                DummyFace(bbox=(10, 10, 50, 50)),
                DummyFace(bbox=(60, 60, 100, 100)),
            ]
            resp_multi = self.client.post(
                "/enrollment/quality",
                headers=self.AUTH_HEADERS,
                files={"file": ("test.jpg", img_bytes, "image/jpeg")},
            )
            self.assertEqual(resp_multi.status_code, 422)
            self.assertEqual(resp_multi.json()["detail"], "MULTIPLE_FACES")

            # Face too small -> 422
            self.fake_engine.faces = [DummyFace(bbox=(50, 50, 130, 130))]
            resp_small = self.client.post(
                "/enrollment/quality",
                headers=self.AUTH_HEADERS,
                files={"file": ("test.jpg", img_bytes, "image/jpeg")},
            )
            self.assertEqual(resp_small.status_code, 422)
            self.assertEqual(resp_small.json()["detail"], "FACE_TOO_SMALL")

            # Image blurry -> 422
            self.fake_engine.faces = [DummyFace(bbox=(50, 50, 250, 250))]
            img_blurry = encode_image(create_test_image(face_type="blurry"))
            resp_blur = self.client.post(
                "/enrollment/quality",
                headers=self.AUTH_HEADERS,
                files={"file": ("test.jpg", img_blurry, "image/jpeg")},
            )
            self.assertEqual(resp_blur.status_code, 422)
            self.assertEqual(resp_blur.json()["detail"], "IMAGE_BLURRY")

            # Illumination poor -> 422
            img_dark = encode_image(create_test_image(face_type="dark"))
            resp_dark = self.client.post(
                "/enrollment/quality",
                headers=self.AUTH_HEADERS,
                files={"file": ("test.jpg", img_dark, "image/jpeg")},
            )
            self.assertEqual(resp_dark.status_code, 422)
            self.assertEqual(resp_dark.json()["detail"], "ILLUMINATION_POOR")

    def test_enrollment_quality_success_without_embeddings(self):
        # DummyFace without embedding
        face = DummyFace(bbox=(50, 50, 250, 250), det_score=0.99)
        delattr(face, "normed_embedding")
        self.fake_engine.faces = [face]
        img_bytes = encode_image(create_test_image(face_type="valid"))

        with patch.dict(os.environ, {"FACE_SERVICE_SECRET": self.AUTH_SECRET}):
            resp = self.client.post(
                "/enrollment/quality",
                headers=self.AUTH_HEADERS,
                files={"file": ("test.jpg", img_bytes, "image/jpeg")},
            )
            self.assertEqual(resp.status_code, 200)
            data = resp.json()

        self.assertNotIn("embedding", data)
        self.assertEqual(data["imageWidth"], 400)
        self.assertEqual(data["imageHeight"], 400)
        box = data["faceBox"]
        self.assertGreaterEqual(box["x"], 0.0)
        self.assertGreaterEqual(box["y"], 0.0)
        self.assertGreater(box["width"], 0.0)
        self.assertGreater(box["height"], 0.0)
        self.assertLessEqual(box["x"] + box["width"], 1.0)
        self.assertLessEqual(box["y"] + box["height"], 1.0)

        quality = data["quality"]
        self.assertAlmostEqual(quality["detScore"], 0.99, places=2)
        self.assertEqual(quality["faceWidth"], 200)
        self.assertEqual(quality["faceHeight"], 200)
        self.assertGreaterEqual(quality["laplacianVar"], 100.0)
        self.assertGreaterEqual(quality["meanBrightness"], 50.0)
        self.assertLessEqual(quality["meanBrightness"], 205.0)


if __name__ == "__main__":
    unittest.main()
