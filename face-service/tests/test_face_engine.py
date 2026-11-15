import os
import unittest
import cv2
import numpy as np

from face_engine import FaceEngine, QualityCheckError


class DummyFace:
    def __init__(
        self,
        bbox=(50, 50, 250, 250),
        det_score=0.98,
        embedding=None,
    ):
        self.bbox = np.array(bbox, dtype=np.float32)
        self.det_score = det_score
        if embedding is None:
            # Deterministic unit vector of length 512
            emb = np.ones(512, dtype=np.float32)
            emb = emb / np.linalg.norm(emb)
            self.normed_embedding = emb
        else:
            self.normed_embedding = embedding


def create_test_image(
    width=400,
    height=400,
    face_box=(50, 50, 250, 250),
    face_type="valid",
) -> np.ndarray:
    """Creates a synthetic image with specific face crop characteristics.

    face_type options:
    - 'valid': sharp texture with mean brightness around 128
    - 'blurry': solid smooth grayscale with zero/very low laplacian variance
    - 'dark': brightness < 50
    - 'bright': brightness > 205
    """
    img = np.full((height, width, 3), 128, dtype=np.uint8)
    x1, y1, x2, y2 = face_box

    if face_type == "valid":
        # Create high-contrast checkered pattern to yield high Laplacian variance
        grid = np.indices((y2 - y1, x2 - x1))
        checker = (((grid[0] // 8) + (grid[1] // 8)) % 2) * 160 + 40
        for c in range(3):
            img[y1:y2, x1:x2, c] = checker
    elif face_type == "blurry":
        # Solid uniform color has variance ~ 0 (< 100)
        img[y1:y2, x1:x2] = 120
    elif face_type == "dark":
        # High contrast but low mean brightness (between 10 and 50 -> mean ~ 30 < 50)
        grid = np.indices((y2 - y1, x2 - x1))
        checker = (((grid[0] // 4) + (grid[1] // 4)) % 2) * 40 + 10
        for c in range(3):
            img[y1:y2, x1:x2, c] = checker
    elif face_type == "bright":
        # High contrast but high mean brightness (between 215 and 255 -> mean ~ 235 > 205)
        grid = np.indices((y2 - y1, x2 - x1))
        checker = (((grid[0] // 4) + (grid[1] // 4)) % 2) * 40 + 215
        for c in range(3):
            img[y1:y2, x1:x2, c] = checker

    return img


class TestFaceEngineQuality(unittest.TestCase):
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
        # Initialize FaceEngine in lazy mode so no models are loaded
        self.engine = FaceEngine(lazy_load=True)

    def tearDown(self):
        os.environ.clear()
        os.environ.update(self.orig_environ)

    def test_valid_face_quality_evaluation(self):
        img = create_test_image(width=400, height=400, face_box=(50, 50, 250, 250), face_type="valid")
        face = DummyFace(bbox=(50, 50, 250, 250), det_score=0.99)

        result = self.engine.evaluate_enrollment_quality(img, face)

        self.assertEqual(result["modelId"], "buffalo_l")
        self.assertEqual(result["dimension"], 512)
        self.assertEqual(result["preprocessingVersion"], "enrollment-v1")
        self.assertIn("modelVersion", result)
        self.assertEqual(len(result["embedding"]), 512)

        # Norm should be approx 1.0
        emb_arr = np.array(result["embedding"], dtype=np.float32)
        self.assertAlmostEqual(float(np.linalg.norm(emb_arr)), 1.0, places=4)

        quality = result["quality"]
        self.assertEqual(quality["faceWidth"], 200)
        self.assertEqual(quality["faceHeight"], 200)
        self.assertGreaterEqual(quality["laplacianVar"], 100.0)
        self.assertGreaterEqual(quality["meanBrightness"], 50.0)
        self.assertLessEqual(quality["meanBrightness"], 205.0)

    def test_face_too_small_width_and_height(self):
        # Bounding box is 80x80 (below 120x120 threshold)
        img = create_test_image(width=400, height=400, face_box=(50, 50, 130, 130), face_type="valid")
        face = DummyFace(bbox=(50, 50, 130, 130))

        with self.assertRaises(QualityCheckError) as ctx:
            self.engine.evaluate_enrollment_quality(img, face)
        self.assertEqual(ctx.exception.code, "FACE_TOO_SMALL")

    def test_face_too_small_area_ratio(self):
        # 150x150 face in 2000x2000 image -> ratio = 22,500 / 4,000,000 = 0.56% (< 10%)
        img = np.full((2000, 2000, 3), 128, dtype=np.uint8)
        face = DummyFace(bbox=(100, 100, 250, 250))

        with self.assertRaises(QualityCheckError) as ctx:
            self.engine.evaluate_enrollment_quality(img, face)
        self.assertEqual(ctx.exception.code, "FACE_TOO_SMALL")

    def test_face_blurry_detection(self):
        # Smooth image with no edges: laplacian variance < 100
        img = create_test_image(width=400, height=400, face_box=(50, 50, 250, 250), face_type="blurry")
        face = DummyFace(bbox=(50, 50, 250, 250))

        with self.assertRaises(QualityCheckError) as ctx:
            self.engine.evaluate_enrollment_quality(img, face)
        self.assertEqual(ctx.exception.code, "IMAGE_BLURRY")

    def test_face_too_dark_illumination(self):
        img = create_test_image(width=400, height=400, face_box=(50, 50, 250, 250), face_type="dark")
        face = DummyFace(bbox=(50, 50, 250, 250))

        with self.assertRaises(QualityCheckError) as ctx:
            self.engine.evaluate_enrollment_quality(img, face)
        self.assertEqual(ctx.exception.code, "ILLUMINATION_POOR")

    def test_face_too_bright_illumination(self):
        img = create_test_image(width=400, height=400, face_box=(50, 50, 250, 250), face_type="bright")
        face = DummyFace(bbox=(50, 50, 250, 250))

        with self.assertRaises(QualityCheckError) as ctx:
            self.engine.evaluate_enrollment_quality(img, face)
        self.assertEqual(ctx.exception.code, "ILLUMINATION_POOR")

    def test_invalid_embedding_nan_or_infinite(self):
        img = create_test_image(width=400, height=400, face_box=(50, 50, 250, 250), face_type="valid")
        bad_emb = np.ones(512, dtype=np.float32)
        bad_emb[0] = np.nan
        face = DummyFace(bbox=(50, 50, 250, 250), embedding=bad_emb)

        with self.assertRaises(ValueError):
            self.engine.evaluate_enrollment_quality(img, face)

    def test_zero_norm_embedding(self):
        img = create_test_image(width=400, height=400, face_box=(50, 50, 250, 250), face_type="valid")
        zero_emb = np.zeros(512, dtype=np.float32)
        face = DummyFace(bbox=(50, 50, 250, 250), embedding=zero_emb)

        with self.assertRaises(ValueError):
            self.engine.evaluate_enrollment_quality(img, face)

    def test_valid_probe_quality_evaluation(self):
        img = create_test_image(width=400, height=400, face_box=(50, 50, 250, 250), face_type="valid")
        face = DummyFace(bbox=(50, 50, 250, 250), det_score=0.99)

        result = self.engine.evaluate_quality(img, face)

        self.assertNotIn("embedding", result)
        self.assertEqual(result["imageWidth"], 400)
        self.assertEqual(result["imageHeight"], 400)

        box = result["faceBox"]
        self.assertGreaterEqual(box["x"], 0.0)
        self.assertGreaterEqual(box["y"], 0.0)
        self.assertGreater(box["width"], 0.0)
        self.assertGreater(box["height"], 0.0)
        self.assertLessEqual(box["x"] + box["width"], 1.0)
        self.assertLessEqual(box["y"] + box["height"], 1.0)
        self.assertAlmostEqual(box["x"], 50.0 / 400.0, places=3)
        self.assertAlmostEqual(box["y"], 50.0 / 400.0, places=3)
        self.assertAlmostEqual(box["width"], 200.0 / 400.0, places=3)
        self.assertAlmostEqual(box["height"], 200.0 / 400.0, places=3)

        quality = result["quality"]
        self.assertEqual(quality["faceWidth"], 200)
        self.assertEqual(quality["faceHeight"], 200)
        self.assertGreaterEqual(quality["detScore"], 0.0)
        self.assertLessEqual(quality["detScore"], 1.0)
        self.assertGreaterEqual(quality["laplacianVar"], 100.0)
        self.assertGreaterEqual(quality["meanBrightness"], 50.0)
        self.assertLessEqual(quality["meanBrightness"], 205.0)

    def test_evaluate_quality_does_not_require_embedding(self):
        img = create_test_image(width=400, height=400, face_box=(50, 50, 250, 250), face_type="valid")
        face = DummyFace(bbox=(50, 50, 250, 250), det_score=0.98)
        # Deliberately remove embedding attributes
        delattr(face, "normed_embedding")

        # evaluate_quality succeeds without embeddings (detection-only probe)
        result = self.engine.evaluate_quality(img, face)
        self.assertNotIn("embedding", result)
        self.assertEqual(result["imageWidth"], 400)

        # evaluate_enrollment_quality must reject missing embedding
        with self.assertRaises(ValueError) as ctx:
            self.engine.evaluate_enrollment_quality(img, face)
        self.assertIn("missing embedding", str(ctx.exception))

    def test_probe_quality_boundary_failures(self):
        # Blur failure
        img_blur = create_test_image(width=400, height=400, face_box=(50, 50, 250, 250), face_type="blurry")
        face = DummyFace(bbox=(50, 50, 250, 250))
        with self.assertRaises(QualityCheckError) as ctx:
            self.engine.evaluate_quality(img_blur, face)
        self.assertEqual(ctx.exception.code, "IMAGE_BLURRY")

        # Size failure
        img_small = create_test_image(width=400, height=400, face_box=(50, 50, 130, 130), face_type="valid")
        face_small = DummyFace(bbox=(50, 50, 130, 130))
        with self.assertRaises(QualityCheckError) as ctx:
            self.engine.evaluate_quality(img_small, face_small)
        self.assertEqual(ctx.exception.code, "FACE_TOO_SMALL")

        # Illumination failure
        img_dark = create_test_image(width=400, height=400, face_box=(50, 50, 250, 250), face_type="dark")
        with self.assertRaises(QualityCheckError) as ctx:
            self.engine.evaluate_quality(img_dark, face)
        self.assertEqual(ctx.exception.code, "ILLUMINATION_POOR")


if __name__ == "__main__":
    unittest.main()
