package com.pandora.coreservice.service;

import com.pandora.coreservice.config.FaceProperties;
import com.pandora.coreservice.dto.FaceCandidateSampleDto;
import com.pandora.coreservice.dto.FaceEmbeddingResult;
import com.pandora.coreservice.dto.KioskRecognitionResult;
import com.pandora.coreservice.exception.AppException;
import com.pandora.coreservice.exception.ErrorCode;
import com.pandora.coreservice.repo.EmployeeFaceSampleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class KioskRecognitionService {

    private final FaceServiceClient faceServiceClient;
    private final EmployeeFaceSampleRepository employeeFaceSampleRepository;
    private final FaceProperties faceProperties;

    public KioskRecognitionResult recognize(MultipartFile image) {
        FaceEmbeddingResult probeResult;
        try {
            probeResult = faceServiceClient.embedEnrollmentImage(image);
        } catch (AppException e) {
            ErrorCode code = e.getErrorCode();
            if (code == ErrorCode.NO_FACE
                    || code == ErrorCode.MULTIPLE_FACES
                    || code == ErrorCode.FACE_TOO_SMALL
                    || code == ErrorCode.IMAGE_BLURRY
                    || code == ErrorCode.ILLUMINATION_POOR) {
                return new KioskRecognitionResult(false, code.name());
            }
            throw e;
        }

        return matchCandidateSamples(probeResult);
    }

    @Transactional(readOnly = true)
    public KioskRecognitionResult matchCandidateSamples(FaceEmbeddingResult probeResult) {
        List<FaceCandidateSampleDto> candidates = employeeFaceSampleRepository.findActiveCandidateSamples();
        if (candidates == null || candidates.isEmpty()) {
            return new KioskRecognitionResult(false, "NO_ENROLLED_SAMPLES");
        }

        int liveDim = probeResult.getDimension();
        String liveModelId = probeResult.getModelId();
        String liveModelVersion = probeResult.getModelVersion();
        String livePrepVersion = probeResult.getPreprocessingVersion();
        List<Float> probeEmbedding = probeResult.getEmbedding();

        if (probeEmbedding == null || probeEmbedding.size() != liveDim) {
            throw new AppException(ErrorCode.UPSTREAM_FACE_SERVICE_ERROR);
        }

        Map<UUID, Double> employeeScores = new HashMap<>();

        for (FaceCandidateSampleDto candidate : candidates) {
            if (candidate.getEmployeeId() == null || candidate.getEmbedding() == null) {
                continue;
            }
            if (candidate.getDimension() != liveDim) {
                continue;
            }
            if (liveModelId != null && !liveModelId.equalsIgnoreCase(candidate.getModelId())) {
                continue;
            }
            if (liveModelVersion != null && !liveModelVersion.equalsIgnoreCase(candidate.getModelVersion())) {
                continue;
            }
            if (livePrepVersion != null && !livePrepVersion.equalsIgnoreCase(candidate.getPreprocessingVersion())) {
                continue;
            }
            byte[] bytes = candidate.getEmbedding();
            if (bytes.length != liveDim * Float.BYTES) {
                continue;
            }

            ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            float[] sampleVector = new float[liveDim];
            boolean hasNonFinite = false;
            boolean allZero = true;

            for (int i = 0; i < liveDim; i++) {
                float val = buffer.getFloat();
                if (!Float.isFinite(val)) {
                    hasNonFinite = true;
                    break;
                }
                if (val != 0.0f) {
                    allZero = false;
                }
                sampleVector[i] = val;
            }

            if (hasNonFinite || allZero) {
                continue;
            }

            double sim = computeCosineSimilarity(probeEmbedding, sampleVector);
            if (Double.isFinite(sim)) {
                employeeScores.merge(candidate.getEmployeeId(), sim, Math::max);
            }
        }

        if (employeeScores.isEmpty()) {
            return new KioskRecognitionResult(false, "NO_ENROLLED_SAMPLES");
        }

        List<Double> rankedScores = employeeScores.values().stream()
                .sorted(Comparator.reverseOrder())
                .toList();

        double minScore = 0.50;
        double minMargin = 0.08;
        if (faceProperties != null && faceProperties.getRecognition() != null) {
            minScore = faceProperties.getRecognition().getMinScore();
            minMargin = faceProperties.getRecognition().getMinMargin();
        }

        double top1 = rankedScores.get(0);
        double epsilon = 1e-6;

        if (rankedScores.size() == 1) {
            if (top1 >= (minScore - epsilon)) {
                return new KioskRecognitionResult(true, "MATCHED");
            } else {
                return new KioskRecognitionResult(false, "UNKNOWN");
            }
        }

        double top2 = rankedScores.get(1);
        if (top1 < (minScore - epsilon)) {
            return new KioskRecognitionResult(false, "UNKNOWN");
        }
        if ((top1 - top2) < (minMargin - epsilon)) {
            return new KioskRecognitionResult(false, "AMBIGUOUS");
        }

        return new KioskRecognitionResult(true, "MATCHED");
    }

    public static double computeCosineSimilarity(List<Float> u, float[] v) {
        if (u == null || v == null || u.size() != v.length) {
            return -1.0;
        }
        double dot = 0.0;
        double normU = 0.0;
        double normV = 0.0;

        for (int i = 0; i < v.length; i++) {
            Float valU = u.get(i);
            if (valU == null || !Float.isFinite(valU)) {
                return -1.0;
            }
            double du = valU.doubleValue();
            double dv = v[i];
            dot += du * dv;
            normU += du * du;
            normV += dv * dv;
        }

        if (normU <= 0.0 || normV <= 0.0 || !Double.isFinite(dot) || !Double.isFinite(normU) || !Double.isFinite(normV)) {
            return -1.0;
        }

        return dot / (Math.sqrt(normU) * Math.sqrt(normV));
    }
}
