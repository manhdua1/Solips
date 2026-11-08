package com.pandora.coreservice.service;

import com.pandora.coreservice.config.FaceProperties;
import com.pandora.coreservice.dto.FaceConsentReceipt;
import com.pandora.coreservice.dto.FaceConsentRequest;
import com.pandora.coreservice.dto.FaceConsentResponse;
import com.pandora.coreservice.exception.AppException;
import com.pandora.coreservice.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FaceConsentServiceTest {

    private static final String TEST_SECRET = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)
    );

    private FaceProperties faceProperties;
    private MockEnvironment environment;
    private FaceConsentService consentService;

    @BeforeEach
    void setUp() {
        faceProperties = new FaceProperties();
        faceProperties.getConsent().setSecret(TEST_SECRET);
        environment = new MockEnvironment();
        consentService = new FaceConsentService(faceProperties, environment);
    }

    @Test
    @DisplayName("Successfully issues and verifies consent receipt binding employee, admin, revision, timestamp")
    void issueAndVerifyConsent_success() {
        UUID employeeId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        long revision = 2L;

        FaceConsentRequest request = FaceConsentRequest.builder()
                .accepted(true)
                .consentVersion("face-enrollment-v1")
                .expectedRevision(2L)
                .build();

        Instant before = Instant.now().minusSeconds(1);
        FaceConsentResponse response = consentService.issueConsent(employeeId, adminId, revision, request);
        Instant after = Instant.now().plusSeconds(1);

        assertNotNull(response);
        assertNotNull(response.getConsentToken());
        assertEquals("face-enrollment-v1", response.getConsentVersion());
        assertEquals(0, response.getConsentedAt().getNano());
        assertEquals(0, response.getExpiresAt().getNano());
        assertTrue(!response.getConsentedAt().isBefore(before.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)) && response.getConsentedAt().isBefore(after));
        assertEquals(Duration.ofMinutes(10).toSeconds(),
                Duration.between(response.getConsentedAt(), response.getExpiresAt()).toSeconds());

        FaceConsentReceipt receipt = consentService.verifyConsentReceipt(
                response.getConsentToken(), employeeId, adminId, revision
        );

        assertNotNull(receipt);
        assertEquals(employeeId, receipt.getEmployeeId());
        assertEquals(adminId, receipt.getAdminId());
        assertEquals(revision, receipt.getRevision());
        assertEquals("face-enrollment-v1", receipt.getConsentVersion());
        assertEquals(response.getConsentedAt(), receipt.getConsentedAt());
        assertEquals(response.getExpiresAt(), receipt.getExpiresAt());
    }

    @Test
    @DisplayName("Issue consent rejects null or false acceptance with INVALID_CONSENT")
    void issueConsent_notAccepted_throwsInvalidConsent() {
        UUID employeeId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();

        FaceConsentRequest reqNull = FaceConsentRequest.builder().accepted(null).build();
        AppException exNull = assertThrows(AppException.class, () ->
                consentService.issueConsent(employeeId, adminId, 0L, reqNull)
        );
        assertEquals(ErrorCode.INVALID_CONSENT, exNull.getErrorCode());

        FaceConsentRequest reqFalse = FaceConsentRequest.builder().accepted(false).build();
        AppException exFalse = assertThrows(AppException.class, () ->
                consentService.issueConsent(employeeId, adminId, 0L, reqFalse)
        );
        assertEquals(ErrorCode.INVALID_CONSENT, exFalse.getErrorCode());
    }

    @Test
    @DisplayName("Issue consent rejects version mismatch with INVALID_CONSENT_VERSION")
    void issueConsent_versionMismatch_throwsInvalidConsentVersion() {
        UUID employeeId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();

        FaceConsentRequest request = FaceConsentRequest.builder()
                .accepted(true)
                .consentVersion("face-enrollment-v2-wrong")
                .build();

        AppException ex = assertThrows(AppException.class, () ->
                consentService.issueConsent(employeeId, adminId, 0L, request)
        );
        assertEquals(ErrorCode.INVALID_CONSENT_VERSION, ex.getErrorCode());
    }

    @Test
    @DisplayName("Issue consent rejects outdated revision with STALE_REVISION")
    void issueConsent_staleRevision_throwsStaleRevision() {
        UUID employeeId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();

        FaceConsentRequest request = FaceConsentRequest.builder()
                .accepted(true)
                .expectedRevision(1L)
                .build();

        AppException ex = assertThrows(AppException.class, () ->
                consentService.issueConsent(employeeId, adminId, 2L, request)
        );
        assertEquals(ErrorCode.STALE_REVISION, ex.getErrorCode());
    }

    @Test
    @DisplayName("Issue consent requires non-null employeeId and adminId")
    void issueConsent_missingIds_throwsInvalidRequest() {
        FaceConsentRequest req = FaceConsentRequest.builder().accepted(true).build();

        AppException ex1 = assertThrows(AppException.class, () ->
                consentService.issueConsent(null, UUID.randomUUID(), 0L, req)
        );
        assertEquals(ErrorCode.INVALID_REQUEST, ex1.getErrorCode());

        AppException ex2 = assertThrows(AppException.class, () ->
                consentService.issueConsent(UUID.randomUUID(), null, 0L, req)
        );
        assertEquals(ErrorCode.INVALID_REQUEST, ex2.getErrorCode());
    }

    @Test
    @DisplayName("Verify receipt rejects null or blank token")
    void verifyReceipt_blankToken_throwsInvalidConsent() {
        AppException ex1 = assertThrows(AppException.class, () -> consentService.verifyConsentReceipt(null));
        assertEquals(ErrorCode.INVALID_CONSENT, ex1.getErrorCode());

        AppException ex2 = assertThrows(AppException.class, () -> consentService.verifyConsentReceipt("   "));
        assertEquals(ErrorCode.INVALID_CONSENT, ex2.getErrorCode());
    }

    @Test
    @DisplayName("Verify receipt rejects tampered token or invalid signature")
    void verifyReceipt_tamperedToken_throwsInvalidConsent() {
        FaceConsentRequest request = FaceConsentRequest.builder().accepted(true).build();
        FaceConsentResponse response = consentService.issueConsent(
                UUID.randomUUID(), UUID.randomUUID(), 0L, request
        );

        String tampered = response.getConsentToken() + "tampered";
        AppException ex = assertThrows(AppException.class, () ->
                consentService.verifyConsentReceipt(tampered)
        );
        assertEquals(ErrorCode.INVALID_CONSENT, ex.getErrorCode());
    }

    @Test
    @DisplayName("Verify receipt rejects mismatched employeeId or adminId")
    void verifyReceipt_mismatchedIds_throwsInvalidConsent() {
        UUID employeeId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();

        FaceConsentRequest request = FaceConsentRequest.builder().accepted(true).build();
        FaceConsentResponse response = consentService.issueConsent(employeeId, adminId, 0L, request);

        AppException exEmp = assertThrows(AppException.class, () ->
                consentService.verifyConsentReceipt(response.getConsentToken(), UUID.randomUUID(), adminId, 0L)
        );
        assertEquals(ErrorCode.INVALID_CONSENT, exEmp.getErrorCode());

        AppException exAdm = assertThrows(AppException.class, () ->
                consentService.verifyConsentReceipt(response.getConsentToken(), employeeId, UUID.randomUUID(), 0L)
        );
        assertEquals(ErrorCode.INVALID_CONSENT, exAdm.getErrorCode());
    }

    @Test
    @DisplayName("Verify receipt rejects mismatched revision with STALE_REVISION")
    void verifyReceipt_mismatchedRevision_throwsStaleRevision() {
        UUID employeeId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();

        FaceConsentRequest request = FaceConsentRequest.builder().accepted(true).build();
        FaceConsentResponse response = consentService.issueConsent(employeeId, adminId, 3L, request);

        AppException ex = assertThrows(AppException.class, () ->
                consentService.verifyConsentReceipt(response.getConsentToken(), employeeId, adminId, 4L)
        );
        assertEquals(ErrorCode.STALE_REVISION, ex.getErrorCode());
    }

    @Test
    @DisplayName("Lazy secret resolution: missing secret throws IllegalStateException only when invoked")
    void lazySecret_missingSecret_throwsIllegalStateException() {
        FaceProperties emptyProps = new FaceProperties();
        FaceConsentService unconfiguredService = new FaceConsentService(emptyProps, new MockEnvironment());

        assertNotNull(unconfiguredService.getConsentVersion());
        assertNotNull(unconfiguredService.getConsentText());

        FaceConsentRequest req = FaceConsentRequest.builder().accepted(true).build();
        assertThrows(IllegalStateException.class, () ->
                unconfiguredService.issueConsent(UUID.randomUUID(), UUID.randomUUID(), 0L, req)
        );
    }

    @Test
    @DisplayName("Lazy secret resolution: weak secret throws IllegalStateException")
    void lazySecret_weakSecret_throwsIllegalStateException() {
        FaceProperties weakProps = new FaceProperties();
        weakProps.getConsent().setSecret("too-short-secret");
        FaceConsentService weakService = new FaceConsentService(weakProps, new MockEnvironment());

        FaceConsentRequest req = FaceConsentRequest.builder().accepted(true).build();
        assertThrows(IllegalStateException.class, () ->
                weakService.issueConsent(UUID.randomUUID(), UUID.randomUUID(), 0L, req)
        );
    }
}
