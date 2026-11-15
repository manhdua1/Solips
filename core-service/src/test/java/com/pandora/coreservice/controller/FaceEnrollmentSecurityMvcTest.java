package com.pandora.coreservice.controller;

import com.pandora.coreservice.config.SecurityConfig;
import com.pandora.coreservice.dto.FaceDeleteRequest;
import com.pandora.coreservice.dto.FaceEnrollmentResponse;
import com.pandora.coreservice.entity.Role;
import com.pandora.coreservice.entity.User;
import com.pandora.coreservice.exception.GlobalExceptionHandler;
import com.pandora.coreservice.security.JwtService;
import com.pandora.coreservice.service.FaceEnrollmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = FaceEnrollmentController.class)
@Import({SecurityConfig.class, JwtService.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = "JWT_SIGNING_KEY=" + FaceEnrollmentSecurityMvcTest.TEST_SIGNING_KEY_BASE64)
class FaceEnrollmentSecurityMvcTest {

    public static final String TEST_SIGNING_KEY_BASE64 = "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JwtEncoder jwtEncoder;

    @MockitoBean
    private FaceEnrollmentService faceEnrollmentService;

    private UUID adminUserId;
    private String adminToken;
    private String managerToken;
    private String employeeToken;
    private String legacyRolelessToken;

    private final UUID sampleEmployeeId = UUID.randomUUID();

    @BeforeEach
    void setUpTokens() {
        adminUserId = UUID.randomUUID();
        User admin = new User(adminUserId, "admin_user", "hash", Role.Admin, true);
        User manager = new User(UUID.randomUUID(), "manager_user", "hash", Role.Manager, true);
        User employee = new User(UUID.randomUUID(), "employee_user", "hash", Role.Employee, true);

        adminToken = jwtService.generateToken(admin);
        managerToken = jwtService.generateToken(manager);
        employeeToken = jwtService.generateToken(employee);

        Instant now = Instant.now();
        JwtClaimsSet legacyClaims = JwtClaimsSet.builder()
                .subject(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        legacyRolelessToken = jwtEncoder.encode(JwtEncoderParameters.from(header, legacyClaims)).getTokenValue();
    }

    private static String validDeleteJson() {
        return "{\"expectedRevision\":0}";
    }

    // =========================================================================
    // 1. ANONYMOUS ACCESS (401 UNAUTHENTICATED)
    // =========================================================================

    @Test
    @DisplayName("Anonymous GET /api/employees/{id}/face returns 401 UNAUTHENTICATED")
    void testGetStatusAnonymous() throws Exception {
        mockMvc.perform(get("/api/employees/{id}/face", sampleEmployeeId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", equalTo(1002)));

        verifyNoInteractions(faceEnrollmentService);
    }

    @Test
    @DisplayName("Anonymous PUT /api/employees/{id}/face multipart returns 401 UNAUTHENTICATED")
    void testPutEnrollMultipartAnonymous() throws Exception {
        MockMultipartFile file1 = new MockMultipartFile("images", "1.jpg", "image/jpeg", new byte[]{1, 2});

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/employees/{id}/face", sampleEmployeeId)
                        .file(file1)
                        .param("expectedRevision", "0"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", equalTo(1002)));

        verifyNoInteractions(faceEnrollmentService);
    }

    @Test
    @DisplayName("Anonymous DELETE /api/employees/{id}/face returns 401 UNAUTHENTICATED")
    void testDeleteAnonymous() throws Exception {
        mockMvc.perform(delete("/api/employees/{id}/face", sampleEmployeeId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", equalTo(1002)));

        verifyNoInteractions(faceEnrollmentService);
    }

    // =========================================================================
    // 2. NON-ADMIN ACCESS (403 ACCESS_DENIED)
    // =========================================================================

    @Test
    @DisplayName("Non-Admin roles (Manager, Employee, Roleless) receive 403 ACCESS_DENIED on GET status")
    void testGetStatusNonAdminRolesForbidden() throws Exception {
        for (String token : new String[]{managerToken, employeeToken, legacyRolelessToken}) {
            mockMvc.perform(get("/api/employees/{id}/face", sampleEmployeeId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code", equalTo(1003)));
        }
        verifyNoInteractions(faceEnrollmentService);
    }

    @Test
    @DisplayName("Non-Admin roles receive 403 ACCESS_DENIED on PUT multipart enrollment")
    void testPutEnrollMultipartNonAdminRolesForbidden() throws Exception {
        MockMultipartFile file = new MockMultipartFile("images", "1.jpg", "image/jpeg", new byte[]{1, 2});

        for (String token : new String[]{managerToken, employeeToken, legacyRolelessToken}) {
            mockMvc.perform(multipart(HttpMethod.PUT, "/api/employees/{id}/face", sampleEmployeeId)
                            .file(file)
                            .param("expectedRevision", "0")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code", equalTo(1003)));
        }
        verifyNoInteractions(faceEnrollmentService);
    }

    @Test
    @DisplayName("Non-Admin roles receive 403 ACCESS_DENIED on DELETE")
    void testDeleteNonAdminRolesForbidden() throws Exception {
        for (String token : new String[]{managerToken, employeeToken, legacyRolelessToken}) {
            mockMvc.perform(delete("/api/employees/{id}/face", sampleEmployeeId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code", equalTo(1003)));
        }
        verifyNoInteractions(faceEnrollmentService);
    }

    // =========================================================================
    // 3. SIGNED ADMIN AUTH (200 OK SUCCESS MOCKS NO DB)
    // =========================================================================

    @Test
    @DisplayName("Signed Admin auth successfully retrieves face status with 200 and Cache-Control no-store")
    void testGetStatusAdminSuccess() throws Exception {
        FaceEnrollmentResponse mockResponse = FaceEnrollmentResponse.builder()
                .employeeId(sampleEmployeeId)
                .registered(true)
                .sampleCount(3)
                .revision(1L)
                .enrolledAt(Instant.now())
                .build();

        when(faceEnrollmentService.getStatus(sampleEmployeeId)).thenReturn(mockResponse);

        mockMvc.perform(get("/api/employees/{id}/face", sampleEmployeeId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.code", equalTo(1000)))
                .andExpect(jsonPath("$.result.employeeId", equalTo(sampleEmployeeId.toString())))
                .andExpect(jsonPath("$.result.registered", equalTo(true)))
                .andExpect(jsonPath("$.result.sampleCount", equalTo(3)))
                .andExpect(jsonPath("$.result.revision", equalTo(1)));

        verify(faceEnrollmentService).getStatus(sampleEmployeeId);
    }

    @Test
    @DisplayName("Signed Admin auth successfully enrolls with 3 multipart images and returns 200 with Cache-Control no-store")
    void testPutEnrollMultipartAdminSuccess() throws Exception {
        MockMultipartFile file1 = new MockMultipartFile("images", "img1.jpg", "image/jpeg", new byte[]{1, 2, 3});
        MockMultipartFile file2 = new MockMultipartFile("images", "img2.jpg", "image/jpeg", new byte[]{4, 5, 6});
        MockMultipartFile file3 = new MockMultipartFile("images", "img3.jpg", "image/png", new byte[]{7, 8, 9});

        FaceEnrollmentResponse mockResponse = FaceEnrollmentResponse.builder()
                .employeeId(sampleEmployeeId)
                .registered(true)
                .sampleCount(3)
                .revision(1L)
                .build();

        when(faceEnrollmentService.enroll(eq(sampleEmployeeId), eq(adminUserId), anyList(), eq(0L)))
                .thenReturn(mockResponse);

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/employees/{id}/face", sampleEmployeeId)
                        .file(file1)
                        .file(file2)
                        .file(file3)
                        .param("expectedRevision", "0")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.code", equalTo(1000)))
                .andExpect(jsonPath("$.result.registered", equalTo(true)))
                .andExpect(jsonPath("$.result.sampleCount", equalTo(3)));

        verify(faceEnrollmentService).enroll(eq(sampleEmployeeId), eq(adminUserId), anyList(), eq(0L));
    }

    @Test
    @DisplayName("Admin PUT enroll with extraneous multipart file part is rejected with 400 INVALID_REQUEST")
    void testPutEnrollMultipartExtraFilePartRejected() throws Exception {
        MockMultipartFile file1 = new MockMultipartFile("images", "img1.jpg", "image/jpeg", new byte[]{1, 2});
        MockMultipartFile extraneous = new MockMultipartFile("unauthorized_part", "bad.jpg", "image/jpeg", new byte[]{3, 4});

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/employees/{id}/face", sampleEmployeeId)
                        .file(file1)
                        .file(extraneous)
                        .param("expectedRevision", "0")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", equalTo(1004)));

        verifyNoInteractions(faceEnrollmentService);
    }

    @Test
    @DisplayName("Admin PUT enroll without expectedRevision returns 400 INVALID_REQUEST")
    void testPutEnrollMissingExpectedRevisionBadRequest() throws Exception {
        MockMultipartFile file1 = new MockMultipartFile("images", "img1.jpg", "image/jpeg", new byte[]{1, 2});

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/employees/{id}/face", sampleEmployeeId)
                        .file(file1)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", equalTo(1004)));

        verifyNoInteractions(faceEnrollmentService);
    }

    @Test
    @DisplayName("Signed Admin auth successfully deletes face enrollment with 200 and Cache-Control no-store")
    void testDeleteAdminSuccess() throws Exception {
        FaceEnrollmentResponse mockResponse = FaceEnrollmentResponse.builder()
                .employeeId(sampleEmployeeId)
                .registered(false)
                .sampleCount(0)
                .revision(1L)
                .build();

        when(faceEnrollmentService.delete(eq(sampleEmployeeId), any(FaceDeleteRequest.class))).thenReturn(mockResponse);

        mockMvc.perform(delete("/api/employees/{id}/face", sampleEmployeeId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validDeleteJson()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.code", equalTo(1000)))
                .andExpect(jsonPath("$.result.registered", equalTo(false)));

        verify(faceEnrollmentService).delete(eq(sampleEmployeeId), any(FaceDeleteRequest.class));
    }

    @Test
    @DisplayName("Admin DELETE without request body returns 400 INVALID_REQUEST and makes no service call")
    void testDeleteAdminWithoutBodyBadRequest() throws Exception {
        mockMvc.perform(delete("/api/employees/{id}/face", sampleEmployeeId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", equalTo(1004)));

        verifyNoInteractions(faceEnrollmentService);
    }
}
