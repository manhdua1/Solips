# tests/test_config.py
"""Tests for environment configuration loading, precedence, and auth readiness."""

import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import cv2
import numpy as np
from fastapi.testclient import TestClient

import main
from config import get_default_env_paths, load_environment, parse_env_file
from face_engine import FaceEngine
from tests.test_face_engine import DummyFace, create_test_image


def encode_image(img: np.ndarray, ext: str = ".jpg") -> bytes:
    success, buffer = cv2.imencode(ext, img)
    if not success:
        raise ValueError(f"Failed to encode image to {ext}")
    return buffer.tobytes()


class FakeEngine:
    def __init__(self, faces=None):
        self.faces = faces if faces is not None else [DummyFace()]
        self.real_engine = FaceEngine(lazy_load=True)
        self.MODEL_ID = self.real_engine.MODEL_ID
        self.DIMENSION = self.real_engine.DIMENSION
        self.PREPROCESSING_VERSION = self.real_engine.PREPROCESSING_VERSION

    def get_model_version(self):
        return self.real_engine.get_model_version()

    def analyze(self, img):
        return self.faces

    def evaluate_enrollment_quality(self, img, face):
        return self.real_engine.evaluate_enrollment_quality(img, face)


class TestConfigLoadingAndOverride(unittest.TestCase):
    def setUp(self):
        self.orig_environ = os.environ.copy()

    def tearDown(self):
        os.environ.clear()
        os.environ.update(self.orig_environ)

    def test_default_env_paths_include_repo_root(self):
        paths = get_default_env_paths()
        self.assertTrue(len(paths) >= 1)
        expected_root_env = Path(__file__).resolve().parent.parent.parent / ".env"
        resolved_paths = [p.resolve() for p in paths]
        self.assertIn(expected_root_env.resolve(), resolved_paths)

    def test_load_environment_reads_file(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            env_file = Path(tmpdir) / ".env"
            env_file.write_text("TEST_CONFIG_KEY=loaded_val\nANOTHER_KEY=123\n", encoding="utf-8")

            # Ensure keys are not in os.environ initially
            os.environ.pop("TEST_CONFIG_KEY", None)
            os.environ.pop("ANOTHER_KEY", None)

            loaded = load_environment(search_paths=[env_file], override=False)
            self.assertEqual(len(loaded), 1)
            self.assertEqual(os.environ.get("TEST_CONFIG_KEY"), "loaded_val")
            self.assertEqual(os.environ.get("ANOTHER_KEY"), "123")

    def test_os_environ_overrides_file_when_override_false(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            env_file = Path(tmpdir) / ".env"
            env_file.write_text("TEST_OVERRIDE_KEY=from_file\n", encoding="utf-8")

            os.environ["TEST_OVERRIDE_KEY"] = "from_os_environment"

            load_environment(search_paths=[env_file], override=False)
            # Environment must take precedence over file values
            self.assertEqual(os.environ["TEST_OVERRIDE_KEY"], "from_os_environment")

    def test_file_overrides_when_override_true(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            env_file = Path(tmpdir) / ".env"
            env_file.write_text("TEST_OVERRIDE_KEY=from_file\n", encoding="utf-8")

            os.environ["TEST_OVERRIDE_KEY"] = "from_os_environment"

            load_environment(search_paths=[env_file], override=True)
            self.assertEqual(os.environ["TEST_OVERRIDE_KEY"], "from_file")

    def test_cwd_independent_discovery(self):
        orig_cwd = os.getcwd()
        with tempfile.TemporaryDirectory() as tmpdir:
            try:
                os.chdir(tmpdir)
                # Inside tmpdir there is no .env
                self.assertFalse((Path(tmpdir) / ".env").exists())

                # Default candidate paths still contain repo root .env
                default_paths = get_default_env_paths()
                resolved = [p.resolve() for p in default_paths]
                expected_root_env = (Path(__file__).resolve().parent.parent.parent / ".env").resolve()
                self.assertIn(expected_root_env, resolved)
            finally:
                os.chdir(orig_cwd)

    def test_missing_file_handled_gracefully(self):
        nonexistent = Path("/nonexistent/path/to/.env")
        loaded = load_environment(search_paths=[nonexistent])
        self.assertEqual(loaded, [])

    def test_fallback_parser_without_dotenv(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            env_file = Path(tmpdir) / ".env"
            env_file.write_text(
                "# Comment line\n"
                "\n"
                "KEY_PLAIN=simple_value\n"
                "KEY_DOUBLE_QUOTED=\"double_quoted_value\"\n"
                "KEY_SINGLE_QUOTED='single_quoted_value'\n"
                "KEY_WITH_EQUALS=foo=bar=baz\n",
                encoding="utf-8",
            )

            os.environ.pop("KEY_PLAIN", None)
            os.environ.pop("KEY_DOUBLE_QUOTED", None)
            os.environ.pop("KEY_SINGLE_QUOTED", None)
            os.environ.pop("KEY_WITH_EQUALS", None)

            parsed = parse_env_file(env_file)
            self.assertEqual(parsed["KEY_PLAIN"], "simple_value")
            self.assertEqual(parsed["KEY_DOUBLE_QUOTED"], "double_quoted_value")
            self.assertEqual(parsed["KEY_SINGLE_QUOTED"], "single_quoted_value")
            self.assertEqual(parsed["KEY_WITH_EQUALS"], "foo=bar=baz")

            # Test load_environment with use_dotenv=False
            loaded = load_environment(search_paths=[env_file], override=False, use_dotenv=False)
            self.assertEqual(len(loaded), 1)
            self.assertEqual(os.environ.get("KEY_PLAIN"), "simple_value")
            self.assertEqual(os.environ.get("KEY_DOUBLE_QUOTED"), "double_quoted_value")
            self.assertEqual(os.environ.get("KEY_SINGLE_QUOTED"), "single_quoted_value")
            self.assertEqual(os.environ.get("KEY_WITH_EQUALS"), "foo=bar=baz")


class TestRequireSecretAuthReadiness(unittest.TestCase):
    """Verifies that endpoints requiring a secret are auth-ready when secret is configured,

    and fail-closed with 503 CONFIG_UNAVAILABLE when secret is missing.
    Does not load any real ONNX model artifacts.
    """

    TEST_SECRET = "test-auth-readiness-secret-32-bytes-long"

    def setUp(self):
        self.original_engine = main.engine
        self.fake_engine = FakeEngine(faces=[DummyFace()])
        main.engine = self.fake_engine
        self.orig_environ = os.environ.copy()
        self.client = TestClient(main.app)

    def tearDown(self):
        main.engine = self.original_engine
        os.environ.clear()
        os.environ.update(self.orig_environ)

    def test_enrollment_embed_fails_closed_when_secret_unset(self):
        # When FACE_SERVICE_SECRET is unset or empty, /enrollment/embed must fail closed with 503
        os.environ["FACE_SERVICE_SECRET"] = ""
        img_bytes = encode_image(create_test_image())

        resp = self.client.post(
            "/enrollment/embed",
            files={"file": ("test.jpg", img_bytes, "image/jpeg")},
        )
        self.assertEqual(resp.status_code, 503)
        self.assertEqual(resp.json()["detail"], "CONFIG_UNAVAILABLE")

    def test_enrollment_embed_auth_readiness_with_configured_secret(self):
        # Configure secret (matching root .env behavior)
        os.environ["FACE_SERVICE_SECRET"] = self.TEST_SECRET
        img_bytes = encode_image(create_test_image())

        # 1. Missing auth credentials -> 401 UNAUTHORIZED (proves service is auth-ready, not 503)
        resp_unauth = self.client.post(
            "/enrollment/embed",
            files={"file": ("test.jpg", img_bytes, "image/jpeg")},
        )
        self.assertEqual(resp_unauth.status_code, 401)
        self.assertEqual(resp_unauth.json()["detail"], "UNAUTHORIZED")

        # 2. Invalid auth key -> 401 UNAUTHORIZED
        resp_invalid = self.client.post(
            "/enrollment/embed",
            headers={"X-Internal-Service-Key": "wrong-secret-key"},
            files={"file": ("test.jpg", img_bytes, "image/jpeg")},
        )
        self.assertEqual(resp_invalid.status_code, 401)
        self.assertEqual(resp_invalid.json()["detail"], "UNAUTHORIZED")

        # 3. Valid X-Internal-Service-Key header -> 200 OK with quality result
        resp_valid_header = self.client.post(
            "/enrollment/embed",
            headers={"X-Internal-Service-Key": self.TEST_SECRET},
            files={"file": ("test.jpg", img_bytes, "image/jpeg")},
        )
        self.assertEqual(resp_valid_header.status_code, 200)
        json_data = resp_valid_header.json()
        self.assertIn("embedding", json_data)
        self.assertEqual(len(json_data["embedding"]), 512)
        self.assertEqual(json_data["dimension"], 512)

        # 4. Valid Authorization Bearer header -> 200 OK
        resp_valid_bearer = self.client.post(
            "/enrollment/embed",
            headers={"Authorization": f"Bearer {self.TEST_SECRET}"},
            files={"file": ("test.jpg", img_bytes, "image/jpeg")},
        )
        self.assertEqual(resp_valid_bearer.status_code, 200)
        self.assertIn("embedding", resp_valid_bearer.json())


class TestFaceQualityThresholdConfiguration(unittest.TestCase):
    """Verifies that face quality thresholds are loaded from environment and applied correctly,

    while preserving code defaults when environment variables are unset.
    """

    THRESHOLD_KEYS = (
        "MIN_FACE_WIDTH",
        "MIN_FACE_HEIGHT",
        "MIN_FACE_AREA_RATIO",
        "MIN_LAPLACIAN_VAR",
        "MIN_BRIGHTNESS",
        "MAX_BRIGHTNESS",
    )

    def setUp(self):
        self.orig_environ = os.environ.copy()
        for k in self.THRESHOLD_KEYS:
            os.environ.pop(k, None)
        self.engine = FaceEngine(lazy_load=True)

    def tearDown(self):
        os.environ.clear()
        os.environ.update(self.orig_environ)

    def test_default_thresholds_applied_when_env_unset(self):
        # 80x80 face in 400x400 image fails under default (120x120, 0.10 ratio)
        img = create_test_image(width=400, height=400, face_box=(50, 50, 130, 130), face_type="valid")
        face = DummyFace(bbox=(50, 50, 130, 130))

        with self.assertRaises(main.QualityCheckError) as ctx:
            self.engine.evaluate_quality(img, face)
        self.assertEqual(ctx.exception.code, "FACE_TOO_SMALL")

    def test_relaxed_thresholds_allow_smaller_and_dimmer_faces(self):
        # Configure local relaxed test thresholds
        os.environ["MIN_FACE_WIDTH"] = "60"
        os.environ["MIN_FACE_HEIGHT"] = "60"
        os.environ["MIN_FACE_AREA_RATIO"] = "0.03"
        os.environ["MIN_LAPLACIAN_VAR"] = "20"
        os.environ["MIN_BRIGHTNESS"] = "25"
        os.environ["MAX_BRIGHTNESS"] = "235"

        # 80x80 face (area 6400 / 160000 = 0.04 >= 0.03) passes under relaxed config
        img = create_test_image(width=400, height=400, face_box=(50, 50, 130, 130), face_type="valid")
        face = DummyFace(bbox=(50, 50, 130, 130))

        result = self.engine.evaluate_quality(img, face)
        self.assertEqual(result["quality"]["faceWidth"], 80)
        self.assertEqual(result["quality"]["faceHeight"], 80)
        self.assertAlmostEqual(result["quality"]["faceAreaRatio"], 0.04, places=2)

    def test_dotenv_thresholds_loaded_into_process(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            env_file = Path(tmpdir) / ".env"
            env_file.write_text(
                "MIN_FACE_WIDTH=60\n"
                "MIN_FACE_HEIGHT=60\n"
                "MIN_FACE_AREA_RATIO=0.03\n"
                "MIN_LAPLACIAN_VAR=20\n"
                "MIN_BRIGHTNESS=25\n"
                "MAX_BRIGHTNESS=235\n",
                encoding="utf-8",
            )
            loaded = load_environment(search_paths=[env_file], override=False)
            self.assertEqual(len(loaded), 1)
            self.assertEqual(os.environ.get("MIN_FACE_WIDTH"), "60")
            self.assertEqual(os.environ.get("MIN_FACE_HEIGHT"), "60")
            self.assertEqual(os.environ.get("MIN_FACE_AREA_RATIO"), "0.03")
            self.assertEqual(os.environ.get("MIN_LAPLACIAN_VAR"), "20")
            self.assertEqual(os.environ.get("MIN_BRIGHTNESS"), "25")
            self.assertEqual(os.environ.get("MAX_BRIGHTNESS"), "235")

