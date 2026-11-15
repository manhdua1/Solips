package com.pandora.coreservice.controller;

import com.pandora.coreservice.config.SecurityConfig;
import com.pandora.coreservice.dto.KioskRecognitionResult;
import com.pandora.coreservice.entity.Role;
import com.pandora.coreservice.entity.User;
import com.pandora.coreservice.exception.AppException;
import com.pandora.coreservice.exception.ErrorCode;
import com.pandora.coreservice.exception.GlobalExceptionHandler;
import com.pandora.coreservice.security.JwtService;
import com.pandora.coreservice.service.FaceEnrollmentService;
import com.pandora.coreservice.service.KioskRecognitionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {KioskController.class, FaceEnrollmentController.class})
@Import({SecurityConfig.class, JwtService.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = "JWT_SIGNING_KEY=" + KioskControllerSecurityMvcTest.TEST_SIGNING_KEY_BASE64)
class KioskControllerSecurityMvcTest {

    public static final String TEST_SIGNING_KEY_BASE64 = "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private KioskRecognitionService kioskRecognitionService;

    @MockitoBean
    private FaceEnrollmentService faceEnrollmentService;

    private String employeeToken;

    @BeforeEach
    void setUp() {
        User employee = new User(UUID.randomUUID(), "emp_user", "hash", Role.Employee, true);
        employeeToken = jwtService.generateToken(employee);
    }

    // =========================================================================
    // 1. PUBLIC NO-TOKEN ACCESS ON EXACT POST ROUTE
    // =========================================================================

    @Test
    @DisplayName("Public anonymous POST /api/kiosk/recognize succeeds without token and returns Cache-Control no-store")
    void testPublicAnonymousPostRecognizeSuccess() throws Exception {
        MockMultipartFile image = new MockMultipartFile("image", "frame.jpg", "image/jpeg", new byte[]{1, 2, 3});
        when(kioskRecognitionService.recognize(any())).thenReturn(new KioskRecognitionResult(true, "MATCHED"));

        mockMvc.perform(multipart(HttpMethod.POST, "/api/kiosk/recognize")
                        .file(image))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.code", equalTo(1000)))
                .andExpect(jsonPath("$.result.success", equalTo(true)))
                .andExpect(jsonPath("$.result.reason", equalTo("MATCHED")));

        verify(kioskRecognitionService).recognize(any());
    }

    @Test
    @DisplayName("Public anonymous POST /api/kiosk/recognize with biometric false reasons returns 200 with code 1000")
    void testPublicAnonymousPostRecognizeFalseReasons() throws Exception {
        MockMultipartFile image = new MockMultipartFile("image", "frame.jpg", "image/jpeg", new byte[]{1, 2, 3});

        for (String reason : new String[]{"NO_FACE", "MULTIPLE_FACES", "FACE_TOO_SMALL", "IMAGE_BLURRY",
                "ILLUMINATION_POOR", "NO_ENROLLED_SAMPLES", "UNKNOWN", "AMBIGUOUS"}) {

            when(kioskRecognitionService.recognize(any())).thenReturn(new KioskRecognitionResult(false, reason));

            mockMvc.perform(multipart(HttpMethod.POST, "/api/kiosk/recognize")
                            .file(image))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", containsString("no-store")))
                    .andExpect(jsonPath("$.code", equalTo(1000)))
                    .andExpect(jsonPath("$.result.success", equalTo(false)))
                    .andExpect(jsonPath("$.result.reason", equalTo(reason)));
        }
    }

    // =========================================================================
    // 2. PERMIT ONLY EXACT POST ROUTE (OTHER METHODS / PATHS REJECTED WITH 401)
    // =========================================================================

    @Test
    @DisplayName("Anonymous GET /api/kiosk/recognize is rejected with 401 UNAUTHENTICATED")
    void testAnonymousGetRecognizeForbidden() throws Exception {
        mockMvc.perform(get("/api/kiosk/recognize"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", equalTo(1002)));

        verify(kioskRecognitionService, never()).recognize(any());
    }

    @Test
    @DisplayName("Anonymous PUT /api/kiosk/recognize is rejected with 401 UNAUTHENTICATED")
    void testAnonymousPutRecognizeForbidden() throws Exception {
        mockMvc.perform(put("/api/kiosk/recognize"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", equalTo(1002)));

        verify(kioskRecognitionService, never()).recognize(any());
    }

    @Test
    @DisplayName("Anonymous DELETE /api/kiosk/recognize is rejected with 401 UNAUTHENTICATED")
    void testAnonymousDeleteRecognizeForbidden() throws Exception {
        mockMvc.perform(delete("/api/kiosk/recognize"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", equalTo(1002)));

        verify(kioskRecognitionService, never()).recognize(any());
    }

    @Test
    @DisplayName("Anonymous GET /api/kiosk/other is rejected with 401 UNAUTHENTICATED")
    void testAnonymousGetOtherKioskRouteForbidden() throws Exception {
        mockMvc.perform(get("/api/kiosk/other"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", equalTo(1002)));

        verify(kioskRecognitionService, never()).recognize(any());
    }

    // =========================================================================
    // 3. PRESERVE ADMIN AUTH ON ENROLLMENT ENDPOINTS
    // =========================================================================

    @Test
    @DisplayName("Enrollment endpoint POST /api/employees/{id}/face/quality preserves Admin requirement")
    void testEnrollmentPreservesAdminRequirement() throws Exception {
        UUID empId = UUID.randomUUID();
        MockMultipartFile image = new MockMultipartFile("image", "probe.jpg", "image/jpeg", new byte[]{1, 2});

        // Anonymous -> 401
        mockMvc.perform(multipart(HttpMethod.POST, "/api/employees/{id}/face/quality", empId)
                        .file(image))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", equalTo(1002)));

        // Non-Admin (Employee role) -> 403
        mockMvc.perform(multipart(HttpMethod.POST, "/api/employees/{id}/face/quality", empId)
                        .file(image)
                        .header("Authorization", "Bearer " + employeeToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", equalTo(1003)));

        verify(faceEnrollmentService, never()).checkQuality(any(), any());
    }

    // =========================================================================
    // 4. MULTIPART EXACTLY 1 IMAGE AND NO EXTRA FILES/FORMFIELDS
    // =========================================================================

    @Test
    @DisplayName("POST recognize with extraneous file part returns 400 INVALID_REQUEST")
    void testExtraneousFilePartRejected() throws Exception {
        MockMultipartFile image = new MockMultipartFile("image", "frame.jpg", "image/jpeg", new byte[]{1, 2});
        MockMultipartFile extra = new MockMultipartFile("extra", "extra.jpg", "image/jpeg", new byte[]{3, 4});

        mockMvc.perform(multipart(HttpMethod.POST, "/api/kiosk/recognize")
                        .file(image)
                        .file(extra))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", equalTo(1004)));

        verify(kioskRecognitionService, never()).recognize(any());
    }

    @Test
    @DisplayName("POST recognize with extraneous form parameter returns 400 INVALID_REQUEST")
    void testExtraneousFormFieldRejected() throws Exception {
        MockMultipartFile image = new MockMultipartFile("image", "frame.jpg", "image/jpeg", new byte[]{1, 2});

        mockMvc.perform(multipart(HttpMethod.POST, "/api/kiosk/recognize")
                        .file(image)
                        .param("unauthorized_param", "evil_value"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", equalTo(1004)));

        verify(kioskRecognitionService, never()).recognize(any());
    }

    @Test
    @DisplayName("POST recognize with wrong field name returns 400 INVALID_REQUEST")
    void testWrongFieldNameRejected() throws Exception {
        MockMultipartFile wrong = new MockMultipartFile("file", "frame.jpg", "image/jpeg", new byte[]{1, 2});

        mockMvc.perform(multipart(HttpMethod.POST, "/api/kiosk/recognize")
                        .file(wrong))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", equalTo(1004)));

        verify(kioskRecognitionService, never()).recognize(any());
    }

    @Test
    @DisplayName("POST recognize with non-multipart payload returns 415 UNSUPPORTED_MEDIA_TYPE")
    void testNonMultipartPayloadRejected() throws Exception {
        mockMvc.perform(post("/api/kiosk/recognize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"image\": \"data\"}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code", equalTo(1011)));

        verify(kioskRecognitionService, never()).recognize(any());
    }

    @Test
    @DisplayName("Infrastructure error from service retains error HTTP status and sanitized response")
    void testServiceInfraErrorKeepsHttpStatus() throws Exception {
        MockMultipartFile image = new MockMultipartFile("image", "frame.jpg", "image/jpeg", new byte[]{1, 2});
        when(kioskRecognitionService.recognize(any())).thenThrow(new AppException(ErrorCode.FACE_SERVICE_TIMEOUT));

        mockMvc.perform(multipart(HttpMethod.POST, "/api/kiosk/recognize")
                        .file(image))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code", equalTo(1020)));
    }
}
