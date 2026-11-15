package com.pandora.coreservice.service;

import com.pandora.coreservice.config.FaceProperties;
import com.pandora.coreservice.dto.FaceEmbedUpstreamResponse;
import com.pandora.coreservice.dto.FaceEmbeddingResult;
import com.pandora.coreservice.dto.FaceQualityCheckResponse;
import com.pandora.coreservice.dto.FaceQualityDto;
import com.pandora.coreservice.dto.FaceBoxDto;
import com.pandora.coreservice.exception.AppException;
import com.pandora.coreservice.exception.ErrorCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

@Service
public class FaceServiceClient {

    public static final String FACE_SERVICE_SECRET_ENV = "FACE_SERVICE_SECRET";
    public static final String FACE_SERVICE_SECRET_PROP = "face.service.secret";
    public static final String INTERNAL_SERVICE_HEADER = "X-Internal-Service-Key";
    public static final String ENROLLMENT_EMBED_PATH = "/enrollment/embed";
    public static final String ENROLLMENT_QUALITY_PATH = "/enrollment/quality";

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/jpg",
            "image/png"
    );

    private final RestClient restClient;
    private final FaceProperties faceProperties;
    private final Environment environment;
    private final ObjectMapper objectMapper;

    @Autowired
    public FaceServiceClient(
            @Qualifier("faceRestClient") RestClient restClient,
            FaceProperties faceProperties,
            Environment environment,
            ObjectProvider<ObjectMapper> objectMapperProvider) {
        this.restClient = restClient;
        this.faceProperties = faceProperties != null ? faceProperties : new FaceProperties();
        this.environment = environment;
        this.objectMapper = objectMapperProvider != null ? objectMapperProvider.getIfAvailable(ObjectMapper::new) : new ObjectMapper();
    }

    public FaceServiceClient(
            RestClient restClient,
            FaceProperties faceProperties,
            Environment environment,
            ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.faceProperties = faceProperties != null ? faceProperties : new FaceProperties();
        this.environment = environment;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public FaceEmbeddingResult embedEnrollmentImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new AppException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        }
        try {
            return embedEnrollmentImage(file.getBytes(), file.getOriginalFilename(), file.getContentType());
        } catch (IOException e) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
    }

    public FaceEmbeddingResult embedEnrollmentImage(byte[] imageBytes, String filename, String contentType) {
        validateInputImage(imageBytes, contentType);
        String normalizedContentType = contentType.toLowerCase().split(";")[0].trim();

        String secretKey = resolveServiceSecret();

        ByteArrayResource resource = new ByteArrayResource(imageBytes) {
            @Override
            public String getFilename() {
                return (filename != null && !filename.isBlank()) ? filename : "image.jpg";
            }
        };

        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(normalizedContentType));
        HttpEntity<ByteArrayResource> fileEntity = new HttpEntity<>(resource, partHeaders);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", fileEntity);

        FaceEmbedUpstreamResponse response;
        try {
            response = restClient.post()
                    .uri(ENROLLMENT_EMBED_PATH)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        if (secretKey != null && !secretKey.isBlank()) {
                            headers.set(INTERNAL_SERVICE_HEADER, secretKey);
                        }
                    })
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, resp) -> {
                        handleUpstreamError(resp.getStatusCode(), resp.getBody());
                    })
                    .body(FaceEmbedUpstreamResponse.class);
        } catch (ResourceAccessException e) {
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (e.getCause() instanceof java.net.SocketTimeoutException
                    || msg.contains("timeout")
                    || msg.contains("timed out")) {
                throw new AppException(ErrorCode.FACE_SERVICE_TIMEOUT);
            }
            throw new AppException(ErrorCode.FACE_SERVICE_UNAVAILABLE);
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw new AppException(ErrorCode.UPSTREAM_FACE_SERVICE_ERROR);
        }

        validateEmbeddingResponse(response);

        byte[] embeddingBytes = FaceEmbeddingResult.floatsToBytes(response.getEmbedding());

        return FaceEmbeddingResult.builder()
                .embedding(response.getEmbedding())
                .embeddingBytes(embeddingBytes)
                .dimension(response.getDimension())
                .modelId(response.getModelId())
                .modelVersion(response.getModelVersion())
                .preprocessingVersion(response.getPreprocessingVersion())
                .quality(response.getQuality())
                .build();
    }

    public FaceQualityCheckResponse checkFaceQuality(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new AppException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        }
        try {
            return checkFaceQuality(file.getBytes(), file.getOriginalFilename(), file.getContentType());
        } catch (IOException e) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
    }

    public FaceQualityCheckResponse checkFaceQuality(byte[] imageBytes, String filename, String contentType) {
        validateInputImage(imageBytes, contentType);
        String normalizedContentType = contentType.toLowerCase().split(";")[0].trim();

        String secretKey = resolveServiceSecret();

        ByteArrayResource resource = new ByteArrayResource(imageBytes) {
            @Override
            public String getFilename() {
                return (filename != null && !filename.isBlank()) ? filename : "probe.jpg";
            }
        };

        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(normalizedContentType));
        HttpEntity<ByteArrayResource> fileEntity = new HttpEntity<>(resource, partHeaders);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", fileEntity);

        FaceQualityCheckResponse response;
        try {
            response = restClient.post()
                    .uri(ENROLLMENT_QUALITY_PATH)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        if (secretKey != null && !secretKey.isBlank()) {
                            headers.set(INTERNAL_SERVICE_HEADER, secretKey);
                        }
                    })
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, resp) -> {
                        handleUpstreamError(resp.getStatusCode(), resp.getBody());
                    })
                    .body(FaceQualityCheckResponse.class);
        } catch (ResourceAccessException e) {
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (e.getCause() instanceof java.net.SocketTimeoutException
                    || msg.contains("timeout")
                    || msg.contains("timed out")) {
                throw new AppException(ErrorCode.FACE_SERVICE_TIMEOUT);
            }
            throw new AppException(ErrorCode.FACE_SERVICE_UNAVAILABLE);
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw new AppException(ErrorCode.UPSTREAM_FACE_SERVICE_ERROR);
        }

        validateQualityResponse(response);
        return response;
    }

    private void validateInputImage(byte[] imageBytes, String contentType) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new AppException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        }

        long maxBytes = 2 * 1024 * 1024L;
        if (faceProperties != null && faceProperties.getImage() != null
                && faceProperties.getImage().getMaxBytes() > 0) {
            maxBytes = faceProperties.getImage().getMaxBytes();
        }

        if (imageBytes.length > maxBytes) {
            throw new AppException(ErrorCode.UPLOAD_TOO_LARGE);
        }

        if (contentType == null || contentType.isBlank()) {
            throw new AppException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        }

        String normalizedType = contentType.toLowerCase().split(";")[0].trim();
        if (!ALLOWED_CONTENT_TYPES.contains(normalizedType)) {
            throw new AppException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        }
    }

    private void validateEmbeddingResponse(FaceEmbedUpstreamResponse response) {
        if (response == null || response.getEmbedding() == null) {
            throw new AppException(ErrorCode.UPSTREAM_FACE_SERVICE_ERROR);
        }

        if (response.getDimension() == null || response.getDimension() != 512
                || response.getEmbedding().size() != 512) {
            throw new AppException(ErrorCode.UPSTREAM_FACE_SERVICE_ERROR);
        }

        List<Float> embedding = response.getEmbedding();
        double sumSq = 0.0;
        for (Float val : embedding) {
            if (val == null || Float.isNaN(val) || Float.isInfinite(val)) {
                throw new AppException(ErrorCode.UPSTREAM_FACE_SERVICE_ERROR);
            }
            sumSq += (val * val);
        }

        double norm = Math.sqrt(sumSq);
        if (Math.abs(norm - 1.0) > 0.05) {
            throw new AppException(ErrorCode.UPSTREAM_FACE_SERVICE_ERROR);
        }

        String expectedModelId = faceProperties.getService().getExpectedModelId();
        if (expectedModelId != null && !expectedModelId.isBlank()
                && !expectedModelId.equalsIgnoreCase(response.getModelId())) {
            throw new AppException(ErrorCode.UPSTREAM_FACE_SERVICE_ERROR);
        }

        String expectedPreprocessing = faceProperties.getService().getExpectedPreprocessingVersion();
        if (expectedPreprocessing != null && !expectedPreprocessing.isBlank()
                && !expectedPreprocessing.equalsIgnoreCase(response.getPreprocessingVersion())) {
            throw new AppException(ErrorCode.UPSTREAM_FACE_SERVICE_ERROR);
        }

        String expectedModelVersion = resolveExpectedModelVersion();
        if (expectedModelVersion != null && !expectedModelVersion.isBlank()
                && !expectedModelVersion.equalsIgnoreCase(response.getModelVersion())) {
            throw new AppException(ErrorCode.UPSTREAM_FACE_SERVICE_ERROR);
        }
    }

    private void validateQualityResponse(FaceQualityCheckResponse response) {
        if (response == null) {
            throw new AppException(ErrorCode.UPSTREAM_FACE_SERVICE_ERROR);
        }
        if (response.getImageWidth() == null || response.getImageWidth() <= 0
                || response.getImageHeight() == null || response.getImageHeight() <= 0) {
            throw new AppException(ErrorCode.UPSTREAM_FACE_SERVICE_ERROR);
        }
        FaceBoxDto box = response.getFaceBox();
        if (box == null
                || box.getX() == null || !Double.isFinite(box.getX()) || box.getX() < 0.0
                || box.getY() == null || !Double.isFinite(box.getY()) || box.getY() < 0.0
                || box.getWidth() == null || !Double.isFinite(box.getWidth()) || box.getWidth() <= 0.0
                || box.getHeight() == null || !Double.isFinite(box.getHeight()) || box.getHeight() <= 0.0
                || (box.getX() + box.getWidth()) > 1.0001
                || (box.getY() + box.getHeight()) > 1.0001) {
            throw new AppException(ErrorCode.UPSTREAM_FACE_SERVICE_ERROR);
        }
        FaceQualityDto q = response.getQuality();
        if (q == null
                || q.getDetScore() == null || !Float.isFinite(q.getDetScore()) || q.getDetScore() < 0.0f || q.getDetScore() > 1.0f
                || q.getFaceWidth() == null || q.getFaceWidth() <= 0
                || q.getFaceHeight() == null || q.getFaceHeight() <= 0
                || q.getFaceAreaRatio() == null || !Float.isFinite(q.getFaceAreaRatio()) || q.getFaceAreaRatio() < 0.0f || q.getFaceAreaRatio() > 1.0f
                || q.getLaplacianVar() == null || !Float.isFinite(q.getLaplacianVar()) || q.getLaplacianVar() < 0.0f
                || q.getMeanBrightness() == null || !Float.isFinite(q.getMeanBrightness()) || q.getMeanBrightness() < 0.0f || q.getMeanBrightness() > 255.0f) {
            throw new AppException(ErrorCode.UPSTREAM_FACE_SERVICE_ERROR);
        }
    }

    private void handleUpstreamError(HttpStatusCode status, InputStream bodyStream) {
        int code = status.value();
        String body = "";
        if (bodyStream != null) {
            try {
                body = new String(bodyStream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException ignored) {}
        }

        if (code == 401 || code == 403) {
            throw new AppException(ErrorCode.UPSTREAM_FACE_SERVICE_ERROR);
        }
        if (code == 413) {
            throw new AppException(ErrorCode.UPLOAD_TOO_LARGE);
        }
        if (code == 415) {
            throw new AppException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        }
        if (code == 422) {
            String detail = extractDetail(body);
            if ("NO_FACE".equalsIgnoreCase(detail)) {
                throw new AppException(ErrorCode.NO_FACE);
            } else if ("MULTIPLE_FACES".equalsIgnoreCase(detail)) {
                throw new AppException(ErrorCode.MULTIPLE_FACES);
            } else if ("FACE_TOO_SMALL".equalsIgnoreCase(detail)) {
                throw new AppException(ErrorCode.FACE_TOO_SMALL);
            } else if ("IMAGE_BLURRY".equalsIgnoreCase(detail)) {
                throw new AppException(ErrorCode.IMAGE_BLURRY);
            } else if ("ILLUMINATION_POOR".equalsIgnoreCase(detail)) {
                throw new AppException(ErrorCode.ILLUMINATION_POOR);
            } else {
                throw new AppException(ErrorCode.UPSTREAM_FACE_SERVICE_ERROR);
            }
        }
        if (code == 504) {
            throw new AppException(ErrorCode.FACE_SERVICE_TIMEOUT);
        }
        if (code == 503) {
            throw new AppException(ErrorCode.FACE_SERVICE_UNAVAILABLE);
        }
        throw new AppException(ErrorCode.UPSTREAM_FACE_SERVICE_ERROR);
    }

    private String extractDetail(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root.has("detail")) {
                JsonNode detail = root.get("detail");
                if (detail.isTextual()) {
                    return detail.asText().trim();
                }
            }
        } catch (Exception ignored) {}

        if (body.contains("NO_FACE")) return "NO_FACE";
        if (body.contains("MULTIPLE_FACES")) return "MULTIPLE_FACES";
        if (body.contains("FACE_TOO_SMALL")) return "FACE_TOO_SMALL";
        if (body.contains("IMAGE_BLURRY")) return "IMAGE_BLURRY";
        if (body.contains("ILLUMINATION_POOR")) return "ILLUMINATION_POOR";

        return body.trim();
    }

    private String resolveServiceSecret() {
        String secret = null;
        if (faceProperties != null && faceProperties.getService() != null) {
            secret = faceProperties.getService().getSecret();
        }
        if (secret == null || secret.isBlank()) {
            if (environment != null) {
                secret = environment.getProperty(FACE_SERVICE_SECRET_ENV);
                if (secret == null || secret.isBlank()) {
                    secret = environment.getProperty(FACE_SERVICE_SECRET_PROP);
                }
            }
        }
        if (secret == null || secret.isBlank()) {
            secret = System.getenv(FACE_SERVICE_SECRET_ENV);
        }
        return secret != null ? secret.trim() : "";
    }

    private String resolveExpectedModelVersion() {
        String ver = null;
        if (faceProperties != null && faceProperties.getService() != null) {
            ver = faceProperties.getService().getExpectedModelVersion();
        }
        if (ver == null || ver.isBlank()) {
            if (environment != null) {
                ver = environment.getProperty("FACE_EXPECTED_MODEL_VERSION");
                if (ver == null || ver.isBlank()) {
                    ver = environment.getProperty("face.service.expected-model-version");
                }
            }
        }
        return ver != null ? ver.trim() : "";
    }
}
