package com.pandora.coreservice.service;

import com.pandora.coreservice.config.FaceProperties;
import com.pandora.coreservice.dto.FaceConsentReceipt;
import com.pandora.coreservice.dto.FaceConsentRequest;
import com.pandora.coreservice.dto.FaceConsentResponse;
import com.pandora.coreservice.dto.FaceDeleteRequest;
import com.pandora.coreservice.dto.FaceEmbeddingResult;
import com.pandora.coreservice.dto.FaceEnrollmentResponse;
import com.pandora.coreservice.entity.Employee;
import com.pandora.coreservice.entity.EmployeeFaceEnrollment;
import com.pandora.coreservice.entity.EmployeeStatus;
import com.pandora.coreservice.exception.AppException;
import com.pandora.coreservice.exception.ErrorCode;
import com.pandora.coreservice.repo.EmployeeFaceEnrollmentRepository;
import com.pandora.coreservice.repo.EmployeeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FaceEnrollmentServiceTest {

    @Mock
    private EmployeeRepository employeeRepository;

    @Mock
    private EmployeeFaceEnrollmentRepository employeeFaceEnrollmentRepository;

    @Mock
    private FaceConsentService faceConsentService;

    @Mock
    private FaceServiceClient faceServiceClient;

    @Mock
    private FaceProperties faceProperties;

    @Mock
    private FaceEnrollmentTransactionService faceEnrollmentTransactionService;

    @InjectMocks
    private FaceEnrollmentService faceEnrollmentService;

    private UUID employeeId;
    private UUID adminId;
    private Employee activeEmployee;
    private FaceProperties.Image imageProperties;

    @BeforeEach
    void setUp() {
        employeeId = UUID.randomUUID();
        adminId = UUID.randomUUID();

        activeEmployee = new Employee();
        activeEmployee.setId(employeeId);
        activeEmployee.setCode("EMP001");
        activeEmployee.setFullName("Jane Doe");
        activeEmployee.setEmail("jane@example.com");
        activeEmployee.setStatus(EmployeeStatus.ACTIVE);
        activeEmployee.setFaceRevision(0L);
        activeEmployee.setDeletedAt(null);

        imageProperties = new FaceProperties.Image();
        imageProperties.setMaxBytes(2 * 1024 * 1024L);
    }

    private List<MultipartFile> createValidThreeImages() {
        return List.of(
                new MockMultipartFile("images", "img1.jpg", "image/jpeg", new byte[]{1, 2, 3}),
                new MockMultipartFile("images", "img2.jpg", "image/jpeg", new byte[]{4, 5, 6}),
                new MockMultipartFile("images", "img3.jpg", "image/png", new byte[]{7, 8, 9})
        );
    }

    private FaceEmbeddingResult createMockEmbeddingResult(int slot) {
        return FaceEmbeddingResult.builder()
                .dimension(512)
                .embedding(Collections.nCopies(512, 1.0f / (float) Math.sqrt(512)))
                .embeddingBytes(new byte[2048])
                .modelId("buffalo_l")
                .modelVersion("1.0")
                .preprocessingVersion("v1")
                .build();
    }

    // --- ENROLL: Success Delegation ---

    @Test
    @DisplayName("Enroll with exactly 3 valid images successfully delegates to client and transaction service")
    void enroll_threeImages_successDelegation() {
        List<MultipartFile> images = createValidThreeImages();
        String consentToken = "valid-consent-token";
        Long expectedRevision = 0L;

        when(faceProperties.getImage()).thenReturn(imageProperties);
        when(employeeRepository.findByIdAndDeletedAtIsNull(employeeId)).thenReturn(Optional.of(activeEmployee));
        when(faceConsentService.getConsentText()).thenReturn("Canonical consent text");
        when(faceConsentService.getConsentVersion()).thenReturn("face-enrollment-v1");

        FaceConsentReceipt mockReceipt = FaceConsentReceipt.builder()
                .employeeId(employeeId)
                .adminId(adminId)
                .revision(0L)
                .consentVersion("face-enrollment-v1")
                .consentedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600))
                .build();
        when(faceConsentService.verifyConsentReceipt(consentToken, employeeId, adminId, 0L))
                .thenReturn(mockReceipt);

        when(faceServiceClient.embedEnrollmentImage(any(MultipartFile.class)))
                .thenReturn(createMockEmbeddingResult(0), createMockEmbeddingResult(1), createMockEmbeddingResult(2));

        FaceEnrollmentResponse mockResponse = FaceEnrollmentResponse.builder()
                .employeeId(employeeId)
                .registered(true)
                .sampleCount(3)
                .revision(1L)
                .consentVersion("face-enrollment-v1")
                .consentText("Canonical consent text")
                .enrolledAt(Instant.now())
                .consentedAt(mockReceipt.getConsentedAt())
                .consentedByAdminId(adminId)
                .acceptedConsentVersion("face-enrollment-v1")
                .build();

        when(faceEnrollmentTransactionService.executeEnrollment(
                eq(employeeId),
                eq(adminId),
                eq(consentToken),
                eq(expectedRevision),
                anyList(),
                eq("Canonical consent text"),
                eq("face-enrollment-v1")
        )).thenReturn(mockResponse);

        FaceEnrollmentResponse response = faceEnrollmentService.enroll(
                employeeId, adminId, images, consentToken, expectedRevision
        );

        assertNotNull(response);
        assertThat(response.isRegistered()).isTrue();
        assertThat(response.getSampleCount()).isEqualTo(3);
        assertThat(response.getRevision()).isEqualTo(1L);

        verify(employeeRepository).findByIdAndDeletedAtIsNull(employeeId);
        verify(faceConsentService).verifyConsentReceipt(consentToken, employeeId, adminId, 0L);
        verify(faceServiceClient, times(3)).embedEnrollmentImage(any(MultipartFile.class));
        verify(faceEnrollmentTransactionService).executeEnrollment(
                eq(employeeId), eq(adminId), eq(consentToken), eq(expectedRevision),
                anyList(), eq("Canonical consent text"), eq("face-enrollment-v1")
        );
    }

    @Test
    @DisplayName("Enroll with array overload delegates to List enroll")
    void enroll_arrayOverload_successDelegation() {
        MultipartFile[] imageArray = createValidThreeImages().toArray(new MultipartFile[0]);
        String consentToken = "valid-consent-token";
        Long expectedRevision = 0L;

        when(faceProperties.getImage()).thenReturn(imageProperties);
        when(employeeRepository.findByIdAndDeletedAtIsNull(employeeId)).thenReturn(Optional.of(activeEmployee));
        when(faceConsentService.getConsentText()).thenReturn("Canonical consent text");
        when(faceConsentService.getConsentVersion()).thenReturn("face-enrollment-v1");

        when(faceServiceClient.embedEnrollmentImage(any(MultipartFile.class)))
                .thenReturn(createMockEmbeddingResult(0));

        FaceEnrollmentResponse mockResponse = FaceEnrollmentResponse.builder()
                .employeeId(employeeId)
                .registered(true)
                .sampleCount(3)
                .revision(1L)
                .build();

        when(faceEnrollmentTransactionService.executeEnrollment(any(), any(), any(), any(), anyList(), any(), any()))
                .thenReturn(mockResponse);

        FaceEnrollmentResponse response = faceEnrollmentService.enroll(
                employeeId, adminId, imageArray, consentToken, expectedRevision
        );

        assertThat(response).isNotNull();
        assertThat(response.isRegistered()).isTrue();
        verify(faceServiceClient, times(3)).embedEnrollmentImage(any(MultipartFile.class));
    }

    // --- ENROLL: Bad Count / Null / Empty List Reject Before Client ---

    @Test
    @DisplayName("Enroll with null image list is rejected with INVALID_REQUEST before client call")
    void enroll_nullImages_throwsInvalidRequestBeforeClient() {
        AppException ex = assertThrows(AppException.class, () ->
                faceEnrollmentService.enroll(employeeId, adminId, (List<MultipartFile>) null, "token", 0L)
        );

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        verifyNoInteractions(faceServiceClient, faceEnrollmentTransactionService, employeeRepository);
    }

    @Test
    @DisplayName("Enroll with fewer than 3 images (count 2) is rejected before client call")
    void enroll_twoImages_throwsInvalidRequestBeforeClient() {
        List<MultipartFile> twoImages = List.of(
                new MockMultipartFile("images", "img1.jpg", "image/jpeg", new byte[]{1}),
                new MockMultipartFile("images", "img2.jpg", "image/jpeg", new byte[]{2})
        );

        AppException ex = assertThrows(AppException.class, () ->
                faceEnrollmentService.enroll(employeeId, adminId, twoImages, "token", 0L)
        );

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        verifyNoInteractions(faceServiceClient, faceEnrollmentTransactionService, employeeRepository);
    }

    @Test
    @DisplayName("Enroll with more than 3 images (count 4) is rejected before client call")
    void enroll_fourImages_throwsInvalidRequestBeforeClient() {
        List<MultipartFile> fourImages = List.of(
                new MockMultipartFile("images", "img1.jpg", "image/jpeg", new byte[]{1}),
                new MockMultipartFile("images", "img2.jpg", "image/jpeg", new byte[]{2}),
                new MockMultipartFile("images", "img3.jpg", "image/jpeg", new byte[]{3}),
                new MockMultipartFile("images", "img4.jpg", "image/jpeg", new byte[]{4})
        );

        AppException ex = assertThrows(AppException.class, () ->
                faceEnrollmentService.enroll(employeeId, adminId, fourImages, "token", 0L)
        );

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        verifyNoInteractions(faceServiceClient, faceEnrollmentTransactionService, employeeRepository);
    }

    // --- ENROLL: Null / Blank Consent Token Reject Before Client ---

    @Test
    @DisplayName("Enroll with null consent token is rejected with INVALID_CONSENT before client call")
    void enroll_nullConsentToken_throwsInvalidConsentBeforeClient() {
        List<MultipartFile> images = createValidThreeImages();

        AppException ex = assertThrows(AppException.class, () ->
                faceEnrollmentService.enroll(employeeId, adminId, images, null, 0L)
        );

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_CONSENT);
        verifyNoInteractions(faceServiceClient, faceEnrollmentTransactionService, employeeRepository);
    }

    @Test
    @DisplayName("Enroll with blank consent token is rejected with INVALID_CONSENT before client call")
    void enroll_blankConsentToken_throwsInvalidConsentBeforeClient() {
        List<MultipartFile> images = createValidThreeImages();

        AppException ex = assertThrows(AppException.class, () ->
                faceEnrollmentService.enroll(employeeId, adminId, images, "   ", 0L)
        );

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_CONSENT);
        verifyNoInteractions(faceServiceClient, faceEnrollmentTransactionService, employeeRepository);
    }

    // --- ENROLL: Null employeeId / adminId Reject Before Client ---

    @Test
    @DisplayName("Enroll with null employeeId or adminId is rejected with INVALID_REQUEST before client call")
    void enroll_nullIds_throwsInvalidRequestBeforeClient() {
        List<MultipartFile> images = createValidThreeImages();

        assertThatThrownBy(() -> faceEnrollmentService.enroll(null, adminId, images, "token", 0L))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REQUEST);

        assertThatThrownBy(() -> faceEnrollmentService.enroll(employeeId, null, images, "token", 0L))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REQUEST);

        verifyNoInteractions(faceServiceClient, faceEnrollmentTransactionService);
    }

    // --- ENROLL: Image validation failures reject before client ---

    @Test
    @DisplayName("Enroll with empty image file is rejected with UNSUPPORTED_MEDIA_TYPE before client call")
    void enroll_emptyImage_throwsUnsupportedMediaTypeBeforeClient() {
        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "img1.jpg", "image/jpeg", new byte[]{1, 2}),
                new MockMultipartFile("images", "img2.jpg", "image/jpeg", new byte[0]),
                new MockMultipartFile("images", "img3.jpg", "image/jpeg", new byte[]{3, 4})
        );
        when(faceProperties.getImage()).thenReturn(imageProperties);

        AppException ex = assertThrows(AppException.class, () ->
                faceEnrollmentService.enroll(employeeId, adminId, images, "token", 0L)
        );

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        verifyNoInteractions(faceServiceClient, faceEnrollmentTransactionService, employeeRepository);
    }

    @Test
    @DisplayName("Enroll with image exceeding maxBytes is rejected with UPLOAD_TOO_LARGE before client call")
    void enroll_oversizedImage_throwsUploadTooLargeBeforeClient() {
        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "img1.jpg", "image/jpeg", new byte[]{1, 2}),
                new MockMultipartFile("images", "img2.jpg", "image/jpeg", new byte[3 * 1024 * 1024]),
                new MockMultipartFile("images", "img3.jpg", "image/jpeg", new byte[]{3, 4})
        );
        when(faceProperties.getImage()).thenReturn(imageProperties);

        AppException ex = assertThrows(AppException.class, () ->
                faceEnrollmentService.enroll(employeeId, adminId, images, "token", 0L)
        );

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.UPLOAD_TOO_LARGE);
        verifyNoInteractions(faceServiceClient, faceEnrollmentTransactionService, employeeRepository);
    }

    @Test
    @DisplayName("Enroll with unsupported image content type is rejected with UNSUPPORTED_MEDIA_TYPE before client call")
    void enroll_unsupportedContentType_throwsUnsupportedMediaTypeBeforeClient() {
        List<MultipartFile> images = List.of(
                new MockMultipartFile("images", "img1.jpg", "image/jpeg", new byte[]{1, 2}),
                new MockMultipartFile("images", "doc.pdf", "application/pdf", new byte[]{3, 4}),
                new MockMultipartFile("images", "img3.jpg", "image/jpeg", new byte[]{5, 6})
        );
        when(faceProperties.getImage()).thenReturn(imageProperties);

        AppException ex = assertThrows(AppException.class, () ->
                faceEnrollmentService.enroll(employeeId, adminId, images, "token", 0L)
        );

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        verifyNoInteractions(faceServiceClient, faceEnrollmentTransactionService, employeeRepository);
    }

    // --- ENROLL: Employee Not Found or Stale Revision Reject Before Client ---

    @Test
    @DisplayName("Enroll when employee not found throws RESOURCE_NOT_FOUND before client call")
    void enroll_employeeNotFound_throwsResourceNotFoundBeforeClient() {
        List<MultipartFile> images = createValidThreeImages();
        when(faceProperties.getImage()).thenReturn(imageProperties);
        when(employeeRepository.findByIdAndDeletedAtIsNull(employeeId)).thenReturn(Optional.empty());

        AppException ex = assertThrows(AppException.class, () ->
                faceEnrollmentService.enroll(employeeId, adminId, images, "token", 0L)
        );

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        verifyNoInteractions(faceServiceClient, faceEnrollmentTransactionService);
    }

    @Test
    @DisplayName("Enroll with stale revision throws STALE_REVISION before client call")
    void enroll_staleRevision_throwsStaleRevisionBeforeClient() {
        List<MultipartFile> images = createValidThreeImages();
        activeEmployee.setFaceRevision(2L); // Employee is on revision 2

        when(faceProperties.getImage()).thenReturn(imageProperties);
        when(employeeRepository.findByIdAndDeletedAtIsNull(employeeId)).thenReturn(Optional.of(activeEmployee));

        // Client passed expectedRevision = 1L (stale)
        AppException ex = assertThrows(AppException.class, () ->
                faceEnrollmentService.enroll(employeeId, adminId, images, "token", 1L)
        );

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.STALE_REVISION);
        verifyNoInteractions(faceServiceClient, faceEnrollmentTransactionService);
    }

    @Test
    @DisplayName("Enroll with consent verification failure throws INVALID_CONSENT before client call")
    void enroll_invalidConsentReceipt_throwsInvalidConsentBeforeClient() {
        List<MultipartFile> images = createValidThreeImages();
        when(faceProperties.getImage()).thenReturn(imageProperties);
        when(employeeRepository.findByIdAndDeletedAtIsNull(employeeId)).thenReturn(Optional.of(activeEmployee));

        when(faceConsentService.verifyConsentReceipt("bad-token", employeeId, adminId, 0L))
                .thenThrow(new AppException(ErrorCode.INVALID_CONSENT));

        AppException ex = assertThrows(AppException.class, () ->
                faceEnrollmentService.enroll(employeeId, adminId, images, "bad-token", 0L)
        );

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_CONSENT);
        verifyNoInteractions(faceServiceClient, faceEnrollmentTransactionService);
    }

    // --- ENROLL: Upstream Quality Error -> No Transaction Service Call ---

    @Test
    @DisplayName("Upstream quality error (NO_FACE) on client call propagates and ensures transaction service is never executed")
    void enroll_upstreamNoFace_noTransactionExecution() {
        List<MultipartFile> images = createValidThreeImages();
        String consentToken = "valid-consent-token";
        Long expectedRevision = 0L;

        when(faceProperties.getImage()).thenReturn(imageProperties);
        when(employeeRepository.findByIdAndDeletedAtIsNull(employeeId)).thenReturn(Optional.of(activeEmployee));
        when(faceConsentService.verifyConsentReceipt(consentToken, employeeId, adminId, 0L))
                .thenReturn(FaceConsentReceipt.builder().build());

        when(faceServiceClient.embedEnrollmentImage(any(MultipartFile.class)))
                .thenThrow(new AppException(ErrorCode.NO_FACE));

        AppException ex = assertThrows(AppException.class, () ->
                faceEnrollmentService.enroll(employeeId, adminId, images, consentToken, expectedRevision)
        );

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.NO_FACE);
        verify(faceEnrollmentTransactionService, never())
                .executeEnrollment(any(), any(), any(), any(), anyList(), any(), any());
    }

    @Test
    @DisplayName("Upstream quality error (IMAGE_BLURRY) on second image stops processing and executes no transaction")
    void enroll_upstreamBlurryOnSecondImage_noTransactionExecution() {
        List<MultipartFile> images = createValidThreeImages();
        String consentToken = "valid-consent-token";

        when(faceProperties.getImage()).thenReturn(imageProperties);
        when(employeeRepository.findByIdAndDeletedAtIsNull(employeeId)).thenReturn(Optional.of(activeEmployee));
        when(faceConsentService.verifyConsentReceipt(consentToken, employeeId, adminId, 0L))
                .thenReturn(FaceConsentReceipt.builder().build());

        // First image succeeds, second image throws IMAGE_BLURRY
        when(faceServiceClient.embedEnrollmentImage(any(MultipartFile.class)))
                .thenReturn(createMockEmbeddingResult(0))
                .thenThrow(new AppException(ErrorCode.IMAGE_BLURRY));

        AppException ex = assertThrows(AppException.class, () ->
                faceEnrollmentService.enroll(employeeId, adminId, images, consentToken, 0L)
        );

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.IMAGE_BLURRY);
        verify(faceServiceClient, times(2)).embedEnrollmentImage(any(MultipartFile.class));
        verify(faceEnrollmentTransactionService, never())
                .executeEnrollment(any(), any(), any(), any(), anyList(), any(), any());
    }

    // --- STATUS TESTS ---

    @Test
    @DisplayName("getStatus with null employeeId throws INVALID_REQUEST")
    void getStatus_nullEmployeeId_throwsInvalidRequest() {
        AppException ex = assertThrows(AppException.class, () -> faceEnrollmentService.getStatus(null));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("getStatus when employee not found throws RESOURCE_NOT_FOUND")
    void getStatus_employeeNotFound_throwsResourceNotFound() {
        when(employeeRepository.findByIdAndDeletedAtIsNull(employeeId)).thenReturn(Optional.empty());

        AppException ex = assertThrows(AppException.class, () -> faceEnrollmentService.getStatus(employeeId));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    @DisplayName("getStatus for unregistered employee returns registered=false and sampleCount=0")
    void getStatus_unregisteredEmployee_returnsUnregisteredResponse() {
        when(employeeRepository.findByIdAndDeletedAtIsNull(employeeId)).thenReturn(Optional.of(activeEmployee));
        when(faceConsentService.getConsentVersion()).thenReturn("face-enrollment-v1");
        when(faceConsentService.getConsentText()).thenReturn("Consent text");
        when(employeeFaceEnrollmentRepository.findByEmployeeId(employeeId)).thenReturn(Optional.empty());

        FaceEnrollmentResponse response = faceEnrollmentService.getStatus(employeeId);

        assertThat(response).isNotNull();
        assertThat(response.isRegistered()).isFalse();
        assertThat(response.getSampleCount()).isZero();
        assertThat(response.getRevision()).isEqualTo(0L);
        assertThat(response.getEnrolledAt()).isNull();
        assertThat(response.getConsentVersion()).isEqualTo("face-enrollment-v1");
    }

    @Test
    @DisplayName("getStatus for registered employee returns registered=true and enrollment metadata")
    void getStatus_registeredEmployee_returnsRegisteredResponse() {
        Instant enrolledAt = Instant.now().minusSeconds(100);
        Instant consentedAt = Instant.now().minusSeconds(120);

        EmployeeFaceEnrollment enrollment = new EmployeeFaceEnrollment();
        enrollment.setEmployeeId(employeeId);
        enrollment.setSampleCount(3);
        enrollment.setEnrolledAt(enrolledAt);
        enrollment.setConsentedAt(consentedAt);
        enrollment.setConsentedByAdminId(adminId);
        enrollment.setConsentVersion("face-enrollment-v1");

        when(employeeRepository.findByIdAndDeletedAtIsNull(employeeId)).thenReturn(Optional.of(activeEmployee));
        when(faceConsentService.getConsentVersion()).thenReturn("face-enrollment-v1");
        when(faceConsentService.getConsentText()).thenReturn("Consent text");
        when(employeeFaceEnrollmentRepository.findByEmployeeId(employeeId)).thenReturn(Optional.of(enrollment));

        FaceEnrollmentResponse response = faceEnrollmentService.getStatus(employeeId);

        assertThat(response).isNotNull();
        assertThat(response.isRegistered()).isTrue();
        assertThat(response.getSampleCount()).isEqualTo(3);
        assertThat(response.getEnrolledAt()).isEqualTo(enrolledAt);
        assertThat(response.getConsentedAt()).isEqualTo(consentedAt);
        assertThat(response.getConsentedByAdminId()).isEqualTo(adminId);
        assertThat(response.getAcceptedConsentVersion()).isEqualTo("face-enrollment-v1");
    }

    // --- ISSUE CONSENT TESTS ---

    @Test
    @DisplayName("issueConsent with null employeeId or adminId throws INVALID_REQUEST")
    void issueConsent_nullIds_throwsInvalidRequest() {
        FaceConsentRequest request = FaceConsentRequest.builder().accepted(true).build();

        assertThrows(AppException.class, () -> faceEnrollmentService.issueConsent(null, adminId, request));
        assertThrows(AppException.class, () -> faceEnrollmentService.issueConsent(employeeId, null, request));
    }

    @Test
    @DisplayName("issueConsent when employee not found throws RESOURCE_NOT_FOUND")
    void issueConsent_employeeNotFound_throwsResourceNotFound() {
        FaceConsentRequest request = FaceConsentRequest.builder().accepted(true).build();
        when(employeeRepository.findByIdAndDeletedAtIsNull(employeeId)).thenReturn(Optional.empty());

        AppException ex = assertThrows(AppException.class, () ->
                faceEnrollmentService.issueConsent(employeeId, adminId, request)
        );
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    @DisplayName("issueConsent successfully delegates to faceConsentService with current revision")
    void issueConsent_success_delegatesToConsentService() {
        activeEmployee.setFaceRevision(3L);
        FaceConsentRequest request = FaceConsentRequest.builder().accepted(true).build();
        FaceConsentResponse mockConsentResponse = FaceConsentResponse.builder()
                .consentToken("receipt-token")
                .consentVersion("face-enrollment-v1")
                .consentedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600))
                .build();

        when(employeeRepository.findByIdAndDeletedAtIsNull(employeeId)).thenReturn(Optional.of(activeEmployee));
        when(faceConsentService.issueConsent(employeeId, adminId, 3L, request)).thenReturn(mockConsentResponse);

        FaceConsentResponse response = faceEnrollmentService.issueConsent(employeeId, adminId, request);

        assertThat(response).isNotNull();
        assertThat(response.getConsentToken()).isEqualTo("receipt-token");
        verify(faceConsentService).issueConsent(employeeId, adminId, 3L, request);
    }

    // --- DELETE TESTS ---

    @Test
    @DisplayName("delete with null employeeId throws INVALID_REQUEST")
    void delete_nullEmployeeId_throwsInvalidRequest() {
        AppException ex = assertThrows(AppException.class, () -> faceEnrollmentService.delete(null, 0L));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("delete successfully delegates to faceEnrollmentTransactionService")
    void delete_success_delegatesToTransactionService() {
        when(faceConsentService.getConsentText()).thenReturn("Consent text");
        when(faceConsentService.getConsentVersion()).thenReturn("face-enrollment-v1");

        FaceEnrollmentResponse mockResponse = FaceEnrollmentResponse.builder()
                .employeeId(employeeId)
                .registered(false)
                .sampleCount(0)
                .revision(1L)
                .build();

        when(faceEnrollmentTransactionService.executeDelete(
                eq(employeeId), eq(0L), eq("Consent text"), eq("face-enrollment-v1")
        )).thenReturn(mockResponse);

        FaceEnrollmentResponse response = faceEnrollmentService.delete(employeeId, 0L);

        assertThat(response).isNotNull();
        assertThat(response.isRegistered()).isFalse();
        verify(faceEnrollmentTransactionService).executeDelete(
                eq(employeeId), eq(0L), eq("Consent text"), eq("face-enrollment-v1")
        );
    }

    @Test
    @DisplayName("delete with FaceDeleteRequest overload extracts revision")
    void delete_requestOverload_delegatesProperly() {
        when(faceConsentService.getConsentText()).thenReturn("Consent text");
        when(faceConsentService.getConsentVersion()).thenReturn("face-enrollment-v1");

        FaceDeleteRequest request = FaceDeleteRequest.builder().expectedRevision(2L).build();
        FaceEnrollmentResponse mockResponse = FaceEnrollmentResponse.builder()
                .employeeId(employeeId)
                .registered(false)
                .sampleCount(0)
                .revision(3L)
                .build();

        when(faceEnrollmentTransactionService.executeDelete(
                eq(employeeId), eq(2L), eq("Consent text"), eq("face-enrollment-v1")
        )).thenReturn(mockResponse);

        FaceEnrollmentResponse response = faceEnrollmentService.delete(employeeId, request);

        assertThat(response).isNotNull();
        verify(faceEnrollmentTransactionService).executeDelete(
                eq(employeeId), eq(2L), eq("Consent text"), eq("face-enrollment-v1")
        );
    }
}
