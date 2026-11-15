package com.pandora.coreservice.service;

import com.pandora.coreservice.config.FaceProperties;
import com.pandora.coreservice.dto.FaceCandidateSampleDto;
import com.pandora.coreservice.dto.FaceEmbeddingResult;
import com.pandora.coreservice.dto.KioskRecognitionResult;
import com.pandora.coreservice.exception.AppException;
import com.pandora.coreservice.exception.ErrorCode;
import com.pandora.coreservice.repo.EmployeeFaceSampleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KioskRecognitionServiceTest {

    @Mock
    private FaceServiceClient faceServiceClient;

    @Mock
    private EmployeeFaceSampleRepository employeeFaceSampleRepository;

    private FaceProperties faceProperties;
    private KioskRecognitionService service;

    private static final int DIM = 512;
    private static final String MODEL_ID = "buffalo_l";
    private static final String MODEL_VER = "test-hash-123";
    private static final String PREPROC_VER = "enrollment-v1";

    @BeforeEach
    void setUp() {
        faceProperties = new FaceProperties();
        faceProperties.getRecognition().setMinScore(0.50);
        faceProperties.getRecognition().setMinMargin(0.08);

        service = new KioskRecognitionService(
                faceServiceClient,
                employeeFaceSampleRepository,
                faceProperties
        );
    }

    private static byte[] createVectorBytes(float... initialValues) {
        ByteBuffer buffer = ByteBuffer.allocate(DIM * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < DIM; i++) {
            float val = (i < initialValues.length) ? initialValues[i] : 0.0f;
            buffer.putFloat(val);
        }
        return buffer.array();
    }

    private static List<Float> createProbeEmbedding(float... initialValues) {
        List<Float> list = new ArrayList<>(DIM);
        for (int i = 0; i < DIM; i++) {
            float val = (i < initialValues.length) ? initialValues[i] : 0.0f;
            list.add(val);
        }
        return list;
    }

    private static FaceEmbeddingResult createMockProbeResult(List<Float> embedding) {
        return FaceEmbeddingResult.builder()
                .embedding(embedding)
                .dimension(DIM)
                .modelId(MODEL_ID)
                .modelVersion(MODEL_VER)
                .preprocessingVersion(PREPROC_VER)
                .build();
    }

    // =========================================================================
    // 1. COSINE SIMILARITY BOUNDARIES
    // =========================================================================

    @Test
    @DisplayName("Cosine similarity: identical normalized vectors equal 1.0")
    void testCosineIdentical() {
        List<Float> u = createProbeEmbedding(1.0f);
        float[] v = new float[DIM];
        v[0] = 1.0f;

        double sim = KioskRecognitionService.computeCosineSimilarity(u, v);
        assertThat(sim).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    @DisplayName("Cosine similarity: orthogonal vectors equal 0.0")
    void testCosineOrthogonal() {
        List<Float> u = createProbeEmbedding(1.0f, 0.0f);
        float[] v = new float[DIM];
        v[0] = 0.0f;
        v[1] = 1.0f;

        double sim = KioskRecognitionService.computeCosineSimilarity(u, v);
        assertThat(sim).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    @DisplayName("Cosine similarity: opposite vectors equal -1.0")
    void testCosineOpposite() {
        List<Float> u = createProbeEmbedding(1.0f);
        float[] v = new float[DIM];
        v[0] = -1.0f;

        double sim = KioskRecognitionService.computeCosineSimilarity(u, v);
        assertThat(sim).isCloseTo(-1.0, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    @DisplayName("Cosine similarity: handles non-finite values safely by returning -1.0")
    void testCosineNonFinite() {
        List<Float> u = createProbeEmbedding(Float.NaN);
        float[] v = new float[DIM];
        v[0] = 1.0f;

        double sim = KioskRecognitionService.computeCosineSimilarity(u, v);
        assertThat(sim).isEqualTo(-1.0);
    }

    // =========================================================================
    // 2. UPSTREAM QUALITY AND INFRASTRUCTURE ERROR MAPPING
    // =========================================================================

    @Test
    @DisplayName("Upstream quality errors map to 200 OK reasons with success false")
    void testUpstreamQualityErrorMapping() {
        MockMultipartFile file = new MockMultipartFile("image", "test.jpg", "image/jpeg", new byte[]{1, 2});

        for (ErrorCode code : List.of(
                ErrorCode.NO_FACE,
                ErrorCode.MULTIPLE_FACES,
                ErrorCode.FACE_TOO_SMALL,
                ErrorCode.IMAGE_BLURRY,
                ErrorCode.ILLUMINATION_POOR)) {

            org.mockito.Mockito.doThrow(new AppException(code)).when(faceServiceClient).embedEnrollmentImage(file);

            KioskRecognitionResult result = service.recognize(file);
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getReason()).isEqualTo(code.name());
        }

        verify(employeeFaceSampleRepository, never()).findActiveCandidateSamples();
    }

    @Test
    @DisplayName("Upstream infrastructure and media errors are rethrown (not converted to 200 reasons)")
    void testUpstreamInfraErrorsRethrown() {
        MockMultipartFile file = new MockMultipartFile("image", "test.jpg", "image/jpeg", new byte[]{1, 2});

        for (ErrorCode code : List.of(
                ErrorCode.UPLOAD_TOO_LARGE,
                ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                ErrorCode.UPSTREAM_FACE_SERVICE_ERROR,
                ErrorCode.FACE_SERVICE_TIMEOUT,
                ErrorCode.FACE_SERVICE_UNAVAILABLE)) {

            org.mockito.Mockito.doThrow(new AppException(code)).when(faceServiceClient).embedEnrollmentImage(file);

            assertThatThrownBy(() -> service.recognize(file))
                    .isInstanceOf(AppException.class)
                    .extracting(e -> ((AppException) e).getErrorCode())
                    .isEqualTo(code);
        }

        verify(employeeFaceSampleRepository, never()).findActiveCandidateSamples();
    }

    // =========================================================================
    // 3. CANDIDATE FILTERING (CORRUPT, INCOMPATIBLE, EMPTY) & NO WRITES
    // =========================================================================

    @Test
    @DisplayName("Empty candidate samples returns NO_ENROLLED_SAMPLES without writing to DB")
    void testNoCandidateSamples() {
        MockMultipartFile file = new MockMultipartFile("image", "test.jpg", "image/jpeg", new byte[]{1, 2});
        when(faceServiceClient.embedEnrollmentImage(file)).thenReturn(createMockProbeResult(createProbeEmbedding(1.0f)));
        when(employeeFaceSampleRepository.findActiveCandidateSamples()).thenReturn(Collections.emptyList());

        KioskRecognitionResult result = service.recognize(file);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getReason()).isEqualTo("NO_ENROLLED_SAMPLES");

        verify(employeeFaceSampleRepository).findActiveCandidateSamples();
        verifyNoMoreInteractions(employeeFaceSampleRepository);
    }

    @Test
    @DisplayName("Corrupt and incompatible candidate samples are skipped; if all skipped, returns NO_ENROLLED_SAMPLES")
    void testCorruptAndIncompatibleCandidatesSkipped() {
        MockMultipartFile file = new MockMultipartFile("image", "test.jpg", "image/jpeg", new byte[]{1, 2});
        when(faceServiceClient.embedEnrollmentImage(file)).thenReturn(createMockProbeResult(createProbeEmbedding(1.0f)));

        UUID emp1 = UUID.randomUUID();

        // 1. Wrong dimension
        FaceCandidateSampleDto wrongDim = new FaceCandidateSampleDto(emp1, createVectorBytes(1.0f), 256, MODEL_ID, MODEL_VER, PREPROC_VER);
        // 2. Wrong modelId
        FaceCandidateSampleDto wrongModel = new FaceCandidateSampleDto(emp1, createVectorBytes(1.0f), DIM, "other_model", MODEL_VER, PREPROC_VER);
        // 3. Wrong modelVersion
        FaceCandidateSampleDto wrongVer = new FaceCandidateSampleDto(emp1, createVectorBytes(1.0f), DIM, MODEL_ID, "different_hash", PREPROC_VER);
        // 4. Wrong preprocessing
        FaceCandidateSampleDto wrongPrep = new FaceCandidateSampleDto(emp1, createVectorBytes(1.0f), DIM, MODEL_ID, MODEL_VER, "prep-v2");
        // 5. Corrupt byte length
        FaceCandidateSampleDto truncatedBytes = new FaceCandidateSampleDto(emp1, new byte[]{1, 2, 3}, DIM, MODEL_ID, MODEL_VER, PREPROC_VER);
        // 6. All zero vector
        FaceCandidateSampleDto allZero = new FaceCandidateSampleDto(emp1, new byte[DIM * Float.BYTES], DIM, MODEL_ID, MODEL_VER, PREPROC_VER);
        // 7. NaN in vector
        ByteBuffer nanBuf = ByteBuffer.allocate(DIM * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        nanBuf.putFloat(Float.NaN);
        for (int i = 1; i < DIM; i++) nanBuf.putFloat(0.5f);
        FaceCandidateSampleDto nanVector = new FaceCandidateSampleDto(emp1, nanBuf.array(), DIM, MODEL_ID, MODEL_VER, PREPROC_VER);

        when(employeeFaceSampleRepository.findActiveCandidateSamples()).thenReturn(List.of(
                wrongDim, wrongModel, wrongVer, wrongPrep, truncatedBytes, allZero, nanVector
        ));

        KioskRecognitionResult result = service.recognize(file);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getReason()).isEqualTo("NO_ENROLLED_SAMPLES");

        // Verify zero writes occurred
        verify(employeeFaceSampleRepository).findActiveCandidateSamples();
        verifyNoMoreInteractions(employeeFaceSampleRepository);
    }

    // =========================================================================
    // 4. DISTINCT RANKING & MULTI-SAMPLE MAX AGGREGATION
    // =========================================================================

    @Test
    @DisplayName("Employee score takes MAX across samples; multiple samples for same employee do NOT become top1 and top2")
    void testMaxAcrossSamplesDistinctRanking() {
        MockMultipartFile file = new MockMultipartFile("image", "test.jpg", "image/jpeg", new byte[]{1, 2});
        // Probe is [1, 0, ...]
        when(faceServiceClient.embedEnrollmentImage(file)).thenReturn(createMockProbeResult(createProbeEmbedding(1.0f, 0.0f)));

        UUID empA = UUID.randomUUID();
        UUID empB = UUID.randomUUID();

        // EmpA has 3 samples:
        // Sample 1: sim ~ 0.90
        byte[] bytesA1 = createVectorBytes(0.90f, 0.43588989f);
        // Sample 2: sim ~ 0.96 (MAX for EmpA)
        byte[] bytesA2 = createVectorBytes(0.96f, 0.28f);
        // Sample 3: sim ~ 0.85
        byte[] bytesA3 = createVectorBytes(0.85f, 0.52678f);

        // EmpB has 1 sample: sim ~ 0.70
        byte[] bytesB = createVectorBytes(0.70f, 0.7141428f);

        when(employeeFaceSampleRepository.findActiveCandidateSamples()).thenReturn(List.of(
                new FaceCandidateSampleDto(empA, bytesA1, DIM, MODEL_ID, MODEL_VER, PREPROC_VER),
                new FaceCandidateSampleDto(empA, bytesA2, DIM, MODEL_ID, MODEL_VER, PREPROC_VER),
                new FaceCandidateSampleDto(empA, bytesA3, DIM, MODEL_ID, MODEL_VER, PREPROC_VER),
                new FaceCandidateSampleDto(empB, bytesB, DIM, MODEL_ID, MODEL_VER, PREPROC_VER)
        ));

        KioskRecognitionResult result = service.recognize(file);
        // EmpA max is 0.96, EmpB is 0.70.
        // Diff = 0.96 - 0.70 = 0.26 >= 0.08 margin -> MATCHED
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getReason()).isEqualTo("MATCHED");
    }

    // =========================================================================
    // 5. ONE CANDIDATE EMPLOYEE (THRESHOLD ONLY)
    // =========================================================================

    @Test
    @DisplayName("Single enrolled candidate matching threshold (>= 0.50) returns MATCHED")
    void testSingleCandidateAboveThresholdMatched() {
        MockMultipartFile file = new MockMultipartFile("image", "test.jpg", "image/jpeg", new byte[]{1, 2});
        when(faceServiceClient.embedEnrollmentImage(file)).thenReturn(createMockProbeResult(createProbeEmbedding(1.0f, 0.0f)));

        UUID emp = UUID.randomUUID();
        // Vector with cosine similarity exactly 0.50
        // [0.5, sqrt(1 - 0.25)] -> [0.5, 0.8660254]
        byte[] bytes = createVectorBytes(0.50f, 0.8660254f);

        when(employeeFaceSampleRepository.findActiveCandidateSamples()).thenReturn(List.of(
                new FaceCandidateSampleDto(emp, bytes, DIM, MODEL_ID, MODEL_VER, PREPROC_VER)
        ));

        KioskRecognitionResult result = service.recognize(file);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getReason()).isEqualTo("MATCHED");
    }

    @Test
    @DisplayName("Single enrolled candidate below threshold (< 0.50) returns UNKNOWN")
    void testSingleCandidateBelowThresholdUnknown() {
        MockMultipartFile file = new MockMultipartFile("image", "test.jpg", "image/jpeg", new byte[]{1, 2});
        when(faceServiceClient.embedEnrollmentImage(file)).thenReturn(createMockProbeResult(createProbeEmbedding(1.0f, 0.0f)));

        UUID emp = UUID.randomUUID();
        // Vector with cosine similarity ~ 0.45 (< 0.50)
        byte[] bytes = createVectorBytes(0.45f, 0.8930285f);

        when(employeeFaceSampleRepository.findActiveCandidateSamples()).thenReturn(List.of(
                new FaceCandidateSampleDto(emp, bytes, DIM, MODEL_ID, MODEL_VER, PREPROC_VER)
        ));

        KioskRecognitionResult result = service.recognize(file);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getReason()).isEqualTo("UNKNOWN");
    }

    // =========================================================================
    // 6. MULTIPLE CANDIDATES: THRESHOLD, MARGIN, AND TIE BOUNDARIES
    // =========================================================================

    @Test
    @DisplayName("Multiple candidates: top1 below minScore (0.50) returns UNKNOWN")
    void testMultipleCandidatesTop1BelowMinScoreReturnsUnknown() {
        MockMultipartFile file = new MockMultipartFile("image", "test.jpg", "image/jpeg", new byte[]{1, 2});
        when(faceServiceClient.embedEnrollmentImage(file)).thenReturn(createMockProbeResult(createProbeEmbedding(1.0f, 0.0f)));

        UUID emp1 = UUID.randomUUID();
        UUID emp2 = UUID.randomUUID();

        // emp1: 0.48, emp2: 0.30
        byte[] b1 = createVectorBytes(0.48f, 0.877268f);
        byte[] b2 = createVectorBytes(0.30f, 0.953939f);

        when(employeeFaceSampleRepository.findActiveCandidateSamples()).thenReturn(List.of(
                new FaceCandidateSampleDto(emp1, b1, DIM, MODEL_ID, MODEL_VER, PREPROC_VER),
                new FaceCandidateSampleDto(emp2, b2, DIM, MODEL_ID, MODEL_VER, PREPROC_VER)
        ));

        KioskRecognitionResult result = service.recognize(file);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getReason()).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("Multiple candidates: top1 >= minScore but margin < 0.08 returns AMBIGUOUS")
    void testMultipleCandidatesInsufficientMarginReturnsAmbiguous() {
        MockMultipartFile file = new MockMultipartFile("image", "test.jpg", "image/jpeg", new byte[]{1, 2});
        when(faceServiceClient.embedEnrollmentImage(file)).thenReturn(createMockProbeResult(createProbeEmbedding(1.0f, 0.0f)));

        UUID emp1 = UUID.randomUUID();
        UUID emp2 = UUID.randomUUID();

        // emp1: 0.80, emp2: 0.75 -> margin is 0.05 < 0.08
        byte[] b1 = createVectorBytes(0.80f, 0.60f);
        byte[] b2 = createVectorBytes(0.75f, 0.6614378f);

        when(employeeFaceSampleRepository.findActiveCandidateSamples()).thenReturn(List.of(
                new FaceCandidateSampleDto(emp1, b1, DIM, MODEL_ID, MODEL_VER, PREPROC_VER),
                new FaceCandidateSampleDto(emp2, b2, DIM, MODEL_ID, MODEL_VER, PREPROC_VER)
        ));

        KioskRecognitionResult result = service.recognize(file);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getReason()).isEqualTo("AMBIGUOUS");
    }

    @Test
    @DisplayName("Multiple candidates: exact tie between top1 and top2 (diff 0.00) returns AMBIGUOUS")
    void testMultipleCandidatesExactTieReturnsAmbiguous() {
        MockMultipartFile file = new MockMultipartFile("image", "test.jpg", "image/jpeg", new byte[]{1, 2});
        when(faceServiceClient.embedEnrollmentImage(file)).thenReturn(createMockProbeResult(createProbeEmbedding(1.0f, 0.0f)));

        UUID emp1 = UUID.randomUUID();
        UUID emp2 = UUID.randomUUID();

        // both have 0.85
        byte[] b1 = createVectorBytes(0.85f, 0.52678f);
        byte[] b2 = createVectorBytes(0.85f, 0.52678f);

        when(employeeFaceSampleRepository.findActiveCandidateSamples()).thenReturn(List.of(
                new FaceCandidateSampleDto(emp1, b1, DIM, MODEL_ID, MODEL_VER, PREPROC_VER),
                new FaceCandidateSampleDto(emp2, b2, DIM, MODEL_ID, MODEL_VER, PREPROC_VER)
        ));

        KioskRecognitionResult result = service.recognize(file);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getReason()).isEqualTo("AMBIGUOUS");
    }

    @Test
    @DisplayName("Multiple candidates: margin exactly 0.08 inclusive returns MATCHED")
    void testMultipleCandidatesExactMarginInclusiveReturnsMatched() {
        MockMultipartFile file = new MockMultipartFile("image", "test.jpg", "image/jpeg", new byte[]{1, 2});
        when(faceServiceClient.embedEnrollmentImage(file)).thenReturn(createMockProbeResult(createProbeEmbedding(1.0f, 0.0f)));

        UUID emp1 = UUID.randomUUID();
        UUID emp2 = UUID.randomUUID();

        // emp1: 0.80, emp2: 0.72 -> margin is 0.08 (exact inclusive)
        byte[] b1 = createVectorBytes(0.80f, 0.60f);
        byte[] b2 = createVectorBytes(0.72f, 0.693974f);

        when(employeeFaceSampleRepository.findActiveCandidateSamples()).thenReturn(List.of(
                new FaceCandidateSampleDto(emp1, b1, DIM, MODEL_ID, MODEL_VER, PREPROC_VER),
                new FaceCandidateSampleDto(emp2, b2, DIM, MODEL_ID, MODEL_VER, PREPROC_VER)
        ));

        KioskRecognitionResult result = service.recognize(file);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getReason()).isEqualTo("MATCHED");
    }
}
