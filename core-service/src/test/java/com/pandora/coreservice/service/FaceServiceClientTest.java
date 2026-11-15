package com.pandora.coreservice.service;

import com.pandora.coreservice.config.FaceProperties;
import com.pandora.coreservice.dto.FaceQualityCheckResponse;
import com.pandora.coreservice.exception.AppException;
import com.pandora.coreservice.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withGatewayTimeout;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withRawStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServiceUnavailable;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

class FaceServiceClientTest {

    private FaceServiceClient client;
    private MockRestServiceServer mockServer;
    private FaceProperties faceProperties;
    private StandardEnvironment environment;
    private ObjectMapper objectMapper;

    private static final String SECRET = "test-secret-key-123";

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();

        faceProperties = new FaceProperties();
        faceProperties.getService().setUrl("http://localhost:8001");
        faceProperties.getService().setSecret(SECRET);

        environment = new StandardEnvironment();
        objectMapper = new ObjectMapper();

        client = new FaceServiceClient(builder.baseUrl("http://localhost:8001").build(), faceProperties, environment, objectMapper);
    }

    private static String validQualityJsonResponse() {
        return """
                {
                    "imageWidth": 1280,
                    "imageHeight": 720,
                    "faceBox": {
                        "x": 0.3,
                        "y": 0.2,
                        "width": 0.3,
                        "height": 0.5
                    },
                    "quality": {
                        "detScore": 0.99,
                        "faceWidth": 384,
                        "faceHeight": 360,
                        "faceAreaRatio": 0.15,
                        "laplacianVar": 150.0,
                        "meanBrightness": 120.0
                    }
                }
                """;
    }

    @Test
    @DisplayName("checkFaceQuality sends explicit secret header and receives valid response")
    void checkFaceQuality_success() {
        mockServer.expect(requestTo("http://localhost:8001/enrollment/quality"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Service-Key", SECRET))
                .andRespond(withSuccess(validQualityJsonResponse(), MediaType.APPLICATION_JSON));

        MockMultipartFile image = new MockMultipartFile("image", "probe.jpg", "image/jpeg", new byte[]{1, 2, 3});
        FaceQualityCheckResponse resp = client.checkFaceQuality(image);

        assertThat(resp).isNotNull();
        assertThat(resp.getImageWidth()).isEqualTo(1280);
        assertThat(resp.getImageHeight()).isEqualTo(720);
        assertThat(resp.getFaceBox().getX()).isEqualTo(0.3);
        assertThat(resp.getFaceBox().getWidth()).isEqualTo(0.3);
        assertThat(resp.getQuality().getDetScore()).isEqualTo(0.99f);

        mockServer.verify();
    }

    @Test
    @DisplayName("Upstream 401 Unauthorized maps to UPSTREAM_FACE_SERVICE_ERROR (1018), NEVER browser JWT 401")
    void checkFaceQuality_upstream401_mapsToUpstreamError() {
        mockServer.expect(requestTo("http://localhost:8001/enrollment/quality"))
                .andRespond(withUnauthorizedRequest());

        MockMultipartFile image = new MockMultipartFile("image", "probe.jpg", "image/jpeg", new byte[]{1, 2, 3});
        AppException ex = assertThrows(AppException.class, () -> client.checkFaceQuality(image));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.UPSTREAM_FACE_SERVICE_ERROR);
        assertThat(ex.getErrorCode().getCode()).isEqualTo(1018);
        assertThat(ex.getErrorCode().getHttpStatus().value()).isEqualTo(502);

        mockServer.verify();
    }

    @Test
    @DisplayName("Upstream 422 quality codes map correctly to 1012..1016")
    void checkFaceQuality_upstream422Errors_mapToDomainCodes() {
        String[][] cases = {
                {"NO_FACE", "1012"},
                {"MULTIPLE_FACES", "1013"},
                {"FACE_TOO_SMALL", "1014"},
                {"IMAGE_BLURRY", "1015"},
                {"ILLUMINATION_POOR", "1016"}
        };

        for (String[] c : cases) {
            String detail = c[0];
            int expectedCode = Integer.parseInt(c[1]);

            mockServer.reset();
            mockServer.expect(requestTo("http://localhost:8001/enrollment/quality"))
                    .andRespond(withRawStatus(422).contentType(MediaType.APPLICATION_JSON).body("{\"detail\":\"" + detail + "\"}"));

            MockMultipartFile image = new MockMultipartFile("image", "probe.jpg", "image/jpeg", new byte[]{1, 2, 3});
            AppException ex = assertThrows(AppException.class, () -> client.checkFaceQuality(image));

            assertThat(ex.getErrorCode().getCode()).isEqualTo(expectedCode);
            mockServer.verify();
        }
    }

    @Test
    @DisplayName("Upstream 504 timeout maps to FACE_SERVICE_TIMEOUT (1020)")
    void checkFaceQuality_upstream504_mapsToTimeout() {
        mockServer.expect(requestTo("http://localhost:8001/enrollment/quality"))
                .andRespond(withGatewayTimeout());

        MockMultipartFile image = new MockMultipartFile("image", "probe.jpg", "image/jpeg", new byte[]{1, 2, 3});
        AppException ex = assertThrows(AppException.class, () -> client.checkFaceQuality(image));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FACE_SERVICE_TIMEOUT);
        mockServer.verify();
    }

    @Test
    @DisplayName("Upstream 503 unavailable maps to FACE_SERVICE_UNAVAILABLE (1019)")
    void checkFaceQuality_upstream503_mapsToUnavailable() {
        mockServer.expect(requestTo("http://localhost:8001/enrollment/quality"))
                .andRespond(withServiceUnavailable());

        MockMultipartFile image = new MockMultipartFile("image", "probe.jpg", "image/jpeg", new byte[]{1, 2, 3});
        AppException ex = assertThrows(AppException.class, () -> client.checkFaceQuality(image));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FACE_SERVICE_UNAVAILABLE);
        mockServer.verify();
    }

    @Test
    @DisplayName("Invalid geometry in upstream response throws UPSTREAM_FACE_SERVICE_ERROR")
    void checkFaceQuality_invalidGeometry_throwsUpstreamError() {
        String[] invalidResponses = {
                // Negative x
                "{\"imageWidth\":100,\"imageHeight\":100,\"faceBox\":{\"x\":-0.1,\"y\":0.1,\"width\":0.5,\"height\":0.5},\"quality\":{\"detScore\":0.9,\"faceWidth\":50,\"faceHeight\":50,\"faceAreaRatio\":0.25,\"laplacianVar\":120,\"meanBrightness\":100}}",
                // x + width > 1.0001
                "{\"imageWidth\":100,\"imageHeight\":100,\"faceBox\":{\"x\":0.6,\"y\":0.1,\"width\":0.6,\"height\":0.5},\"quality\":{\"detScore\":0.9,\"faceWidth\":50,\"faceHeight\":50,\"faceAreaRatio\":0.25,\"laplacianVar\":120,\"meanBrightness\":100}}",
                // Zero width
                "{\"imageWidth\":100,\"imageHeight\":100,\"faceBox\":{\"x\":0.1,\"y\":0.1,\"width\":0.0,\"height\":0.5},\"quality\":{\"detScore\":0.9,\"faceWidth\":50,\"faceHeight\":50,\"faceAreaRatio\":0.25,\"laplacianVar\":120,\"meanBrightness\":100}}",
                // detScore > 1.0
                "{\"imageWidth\":100,\"imageHeight\":100,\"faceBox\":{\"x\":0.1,\"y\":0.1,\"width\":0.5,\"height\":0.5},\"quality\":{\"detScore\":1.5,\"faceWidth\":50,\"faceHeight\":50,\"faceAreaRatio\":0.25,\"laplacianVar\":120,\"meanBrightness\":100}}",
                // Negative meanBrightness
                "{\"imageWidth\":100,\"imageHeight\":100,\"faceBox\":{\"x\":0.1,\"y\":0.1,\"width\":0.5,\"height\":0.5},\"quality\":{\"detScore\":0.9,\"faceWidth\":50,\"faceHeight\":50,\"faceAreaRatio\":0.25,\"laplacianVar\":120,\"meanBrightness\":-10}}",
        };

        for (String invalidJson : invalidResponses) {
            mockServer.reset();
            mockServer.expect(requestTo("http://localhost:8001/enrollment/quality"))
                    .andRespond(withSuccess(invalidJson, MediaType.APPLICATION_JSON));

            MockMultipartFile image = new MockMultipartFile("image", "probe.jpg", "image/jpeg", new byte[]{1, 2, 3});
            AppException ex = assertThrows(AppException.class, () -> client.checkFaceQuality(image));

            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.UPSTREAM_FACE_SERVICE_ERROR);
            mockServer.verify();
        }
    }
}
