package com.pandora.coreservice.service;

import com.pandora.coreservice.config.FaceProperties;
import com.pandora.coreservice.dto.FaceDeleteRequest;
import com.pandora.coreservice.dto.FaceEmbeddingResult;
import com.pandora.coreservice.dto.FaceEnrollmentResponse;
import com.pandora.coreservice.dto.FaceQualityCheckResponse;
import com.pandora.coreservice.dto.FaceBoxDto;
import com.pandora.coreservice.dto.FaceQualityDto;
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
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
        Long expectedRevision = 0L;

        when(faceProperties.getImage()).thenReturn(imageProperties);
        when(employeeRepository.findByIdAndDeletedAtIsNull(employeeId)).thenReturn(Optional.of(activeEmployee));

        when(faceServiceClient.embedEnrollmentImage(any(MultipartFile.class)))
                .thenReturn(createMockEmbeddingResult(0), createMockEmbeddingResult(1), createMockEmbeddingResult(2));

        FaceEnrollmentResponse mockResponse = FaceEnrollmentResponse.builder()
                .employeeId(employeeId)
                .registered(true)
                .sampleCount(3)
                .revision(1L)
                .enrolledAt(Instant.now())
                .build();

        when(faceEnrollmentTransactionService.executeEnrollment(
                eq(employeeId),
                eq(adminId),
                eq(expectedRevision),
                anyList()
        )).thenReturn(mockResponse);

        FaceEnrollmentResponse response = faceEnrollmentService.enroll(
                employeeId, adminId, images, expectedRevision
        );

        assertNotNull(response);
        assertThat(response.isRegistered()).isTrue();
        assertThat(response.getSampleCount()).isEqualTo(3);
        assertThat(response.getRevision()).isEqualTo(1L);

        verify(employeeRepository).findByIdAndDeletedAtIsNull(employeeId);
        verify(faceServiceClient, times(3)).embedEnrollmentImage(any(MultipartFile.class));
        verify(faceEnrollmentTransactionService).executeEnrollment(
                eq(employeeId), eq(adminId), eq(expectedRevision), anyList()
        );
    }

    @Test
    @DisplayName("Enroll with array overload delegates to List enroll")
    void enroll_arrayOverload_successDelegation() {
        MultipartFile[] imageArray = createValidThreeImages().toArray(new MultipartFile[0]);
        Long expectedRevision = 0L;

        when(faceProperties.getImage()).thenReturn(imageProperties);
        when(employeeRepository.findByIdAndDeletedAtIsNull(employeeId)).thenReturn(Optional.of(activeEmployee));

        when(faceServiceClient.embedEnrollmentImage(any(MultipartFile.class)))
                .thenReturn(createMockEmbeddingResult(0));

        FaceEnrollmentResponse mockResponse = FaceEnrollmentResponse.builder()
                .employeeId(employeeId)
                .registered(true)
                .sampleCount(3)
                .revision(1L)
                .build();

        when(faceEnrollmentTransactionService.executeEnrollment(any(), any(), any(), anyList()))
                .thenReturn(mockResponse);

        FaceEnrollmentResponse response = faceEnrollmentService.enroll(
                employeeId, adminId, imageArray, expectedRevision
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
                faceEnrollmentService.enroll(employeeId, adminId, (List<MultipartFile>) null, 0L)
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
                faceEnrollmentService.enroll(employeeId, adminId, twoImages, 0L)
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
                faceEnrollmentService.enroll(employeeId, adminId, fourImages, 0L)
        );

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        verifyNoInteractions(faceServiceClient, faceEnrollmentTransactionService, employeeRepository);
    }

    // --- ENROLL: Null employeeId / adminId / expectedRevision Reject Before Client ---

    @Test
    @DisplayName("Enroll with null employeeId or adminId is rejected with INVALID_REQUEST before client call")
    void enroll_nullIds_throwsInvalidRequestBeforeClient() {
        List<MultipartFile> images = createValidThreeImages();

        assertThatThrownBy(() -> faceEnrollmentService.enroll(null, adminId, images, 0L))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REQUEST);

        assertThatThrownBy(() -> faceEnrollmentService.enroll(employeeId, null, images, 0L))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REQUEST);

        verifyNoInteractions(faceServiceClient, faceEnrollmentTransactionService);
    }

    @Test
    @DisplayName("Enroll with null or negative expectedRevision is rejected with INVALID_REQUEST before client call")
    void enroll_invalidRevision_throwsInvalidRequestBeforeClient() {
        List<MultipartFile> images = createValidThreeImages();

        assertThatThrownBy(() -> faceEnrollmentService.enroll(employeeId, adminId, images, (Long) null))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REQUEST);

        assertThatThrownBy(() -> faceEnrollmentService.enroll(employeeId, adminId, images, -1L))
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
                faceEnrollmentService.enroll(employeeId, adminId, images, 0L)
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
                faceEnrollmentService.enroll(employeeId, adminId, images, 0L)
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
                faceEnrollmentService.enroll(employeeId, adminId, images, 0L)
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
                faceEnrollmentService.enroll(employeeId, adminId, images, 0L)
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
                faceEnrollmentService.enroll(employeeId, adminId, images, 1L)
        );

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.STALE_REVISION);
        verifyNoInteractions(faceServiceClient, faceEnrollmentTransactionService);
    }

    // --- ENROLL: Upstream Quality Error -> No Transaction Service Call ---

    @Test
    @DisplayName("Upstream quality error (NO_FACE) on client call propagates and ensures transaction service is never executed")
    void enroll_upstreamNoFace_noTransactionExecution() {
        List<MultipartFile> images = createValidThreeImages();
        Long expectedRevision = 0L;

        when(faceProperties.getImage()).thenReturn(imageProperties);
        when(employeeRepository.findByIdAndDeletedAtIsNull(employeeId)).thenReturn(Optional.of(activeEmployee));

        when(faceServiceClient.embedEnrollmentImage(any(MultipartFile.class)))
                .thenThrow(new AppException(ErrorCode.NO_FACE));

        AppException ex = assertThrows(AppException.class, () ->
                faceEnrollmentService.enroll(employeeId, adminId, images, expectedRevision)
        );

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.NO_FACE);
        verify(faceEnrollmentTransactionService, never())
                .executeEnrollment(any(), any(), any(), anyList());
    }

    @Test
    @DisplayName("Upstream quality error (IMAGE_BLURRY) on second image stops processing and executes no transaction")
    void enroll_upstreamBlurryOnSecondImage_noTransactionExecution() {
        List<MultipartFile> images = createValidThreeImages();

        when(faceProperties.getImage()).thenReturn(imageProperties);
        when(employeeRepository.findByIdAndDeletedAtIsNull(employeeId)).thenReturn(Optional.of(activeEmployee));

        // First image succeeds, second image throws IMAGE_BLURRY
        when(faceServiceClient.embedEnrollmentImage(any(MultipartFile.class)))
                .thenReturn(createMockEmbeddingResult(0))
                .thenThrow(new AppException(ErrorCode.IMAGE_BLURRY));

        AppException ex = assertThrows(AppException.class, () ->
                faceEnrollmentService.enroll(employeeId, adminId, images, 0L)
        );

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.IMAGE_BLURRY);
        verify(faceServiceClient, times(2)).embedEnrollmentImage(any(MultipartFile.class));
        verify(faceEnrollmentTransactionService, never())
                .executeEnrollment(any(), any(), any(), anyList());
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
        when(employeeFaceEnrollmentRepository.findByEmployeeId(employeeId)).thenReturn(Optional.empty());

        FaceEnrollmentResponse response = faceEnrollmentService.getStatus(employeeId);

        assertThat(response).isNotNull();
        assertThat(response.isRegistered()).isFalse();
        assertThat(response.getSampleCount()).isZero();
        assertThat(response.getRevision()).isEqualTo(0L);
        assertThat(response.getEnrolledAt()).isNull();
    }

    @Test
    @DisplayName("getStatus for registered employee returns registered=true and enrollment metadata")
    void getStatus_registeredEmployee_returnsRegisteredResponse() {
        Instant enrolledAt = Instant.now().minusSeconds(100);

        EmployeeFaceEnrollment enrollment = new EmployeeFaceEnrollment();
        enrollment.setEmployeeId(employeeId);
        enrollment.setSampleCount(3);
        enrollment.setEnrolledAt(enrolledAt);
        enrollment.setEnrolledByAdminId(adminId);

        when(employeeRepository.findByIdAndDeletedAtIsNull(employeeId)).thenReturn(Optional.of(activeEmployee));
        when(employeeFaceEnrollmentRepository.findByEmployeeId(employeeId)).thenReturn(Optional.of(enrollment));

        FaceEnrollmentResponse response = faceEnrollmentService.getStatus(employeeId);

        assertThat(response).isNotNull();
        assertThat(response.isRegistered()).isTrue();
        assertThat(response.getSampleCount()).isEqualTo(3);
        assertThat(response.getEnrolledAt()).isEqualTo(enrolledAt);
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
        FaceEnrollmentResponse mockResponse = FaceEnrollmentResponse.builder()
                .employeeId(employeeId)
                .registered(false)
                .sampleCount(0)
                .revision(1L)
                .build();

        when(faceEnrollmentTransactionService.executeDelete(eq(employeeId), eq(0L))).thenReturn(mockResponse);

        FaceEnrollmentResponse response = faceEnrollmentService.delete(employeeId, 0L);

        assertThat(response).isNotNull();
        assertThat(response.isRegistered()).isFalse();
        verify(faceEnrollmentTransactionService).executeDelete(eq(employeeId), eq(0L));
    }

    @Test
    @DisplayName("delete with FaceDeleteRequest overload extracts revision")
    void delete_requestOverload_delegatesProperly() {
        FaceDeleteRequest request = FaceDeleteRequest.builder().expectedRevision(2L).build();
        FaceEnrollmentResponse mockResponse = FaceEnrollmentResponse.builder()
                .employeeId(employeeId)
                .registered(false)
                .sampleCount(0)
                .revision(3L)
                .build();

        when(faceEnrollmentTransactionService.executeDelete(eq(employeeId), eq(2L))).thenReturn(mockResponse);

        FaceEnrollmentResponse response = faceEnrollmentService.delete(employeeId, request);

        assertThat(response).isNotNull();
        verify(faceEnrollmentTransactionService).executeDelete(eq(employeeId), eq(2L));
    }

    // --- CHECK QUALITY PROBE TESTS ---

    @Test
    @DisplayName("checkQuality with null employeeId throws INVALID_REQUEST")
    void checkQuality_nullEmployeeId_throwsInvalidRequest() {
        MockMultipartFile image = new MockMultipartFile("image", "probe.jpg", "image/jpeg", new byte[]{1, 2, 3});
        AppException ex = assertThrows(AppException.class, () ->
                faceEnrollmentService.checkQuality(null, image)
        );
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        verifyNoInteractions(faceServiceClient, employeeRepository, faceEnrollmentTransactionService);
    }

    @Test
    @DisplayName("checkQuality with empty or null image throws UNSUPPORTED_MEDIA_TYPE")
    void checkQuality_emptyImage_throwsUnsupportedMediaType() {
        MockMultipartFile emptyImage = new MockMultipartFile("image", "probe.jpg", "image/jpeg", new byte[0]);
        AppException ex1 = assertThrows(AppException.class, () ->
                faceEnrollmentService.checkQuality(employeeId, emptyImage)
        );
        assertThat(ex1.getErrorCode()).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE);

        AppException ex2 = assertThrows(AppException.class, () ->
                faceEnrollmentService.checkQuality(employeeId, null)
        );
        assertThat(ex2.getErrorCode()).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE);

        verifyNoInteractions(faceServiceClient, employeeRepository, faceEnrollmentTransactionService);
    }

    @Test
    @DisplayName("checkQuality with oversized image throws UPLOAD_TOO_LARGE")
    void checkQuality_oversizedImage_throwsUploadTooLarge() {
        MockMultipartFile bigImage = new MockMultipartFile("image", "probe.jpg", "image/jpeg", new byte[3 * 1024 * 1024]);
        when(faceProperties.getImage()).thenReturn(imageProperties);

        AppException ex = assertThrows(AppException.class, () ->
                faceEnrollmentService.checkQuality(employeeId, bigImage)
        );
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.UPLOAD_TOO_LARGE);
        verifyNoInteractions(faceServiceClient, employeeRepository, faceEnrollmentTransactionService);
    }

    @Test
    @DisplayName("checkQuality with unsupported MIME type throws UNSUPPORTED_MEDIA_TYPE")
    void checkQuality_unsupportedMime_throwsUnsupportedMediaType() {
        MockMultipartFile doc = new MockMultipartFile("image", "doc.pdf", "application/pdf", new byte[]{1, 2, 3});
        when(faceProperties.getImage()).thenReturn(imageProperties);

        AppException ex = assertThrows(AppException.class, () ->
                faceEnrollmentService.checkQuality(employeeId, doc)
        );
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        verifyNoInteractions(faceServiceClient, employeeRepository, faceEnrollmentTransactionService);
    }

    @Test
    @DisplayName("checkQuality when employee not found throws RESOURCE_NOT_FOUND")
    void checkQuality_employeeNotFound_throwsResourceNotFound() {
        MockMultipartFile image = new MockMultipartFile("image", "probe.jpg", "image/jpeg", new byte[]{1, 2, 3});
        when(faceProperties.getImage()).thenReturn(imageProperties);
        when(employeeRepository.findByIdAndDeletedAtIsNull(employeeId)).thenReturn(Optional.empty());

        AppException ex = assertThrows(AppException.class, () ->
                faceEnrollmentService.checkQuality(employeeId, image)
        );
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        verifyNoInteractions(faceServiceClient, faceEnrollmentTransactionService);
    }

    @Test
    @DisplayName("checkQuality with valid image delegates to faceServiceClient and never calls transaction service or alters revision")
    void checkQuality_success_delegatesToClientWithoutTransactionService() {
        MockMultipartFile image = new MockMultipartFile("image", "probe.jpg", "image/jpeg", new byte[]{1, 2, 3});
        when(faceProperties.getImage()).thenReturn(imageProperties);
        when(employeeRepository.findByIdAndDeletedAtIsNull(employeeId)).thenReturn(Optional.of(activeEmployee));

        FaceQualityCheckResponse mockResponse = FaceQualityCheckResponse.builder()
                .imageWidth(1280)
                .imageHeight(720)
                .faceBox(FaceBoxDto.builder().x(0.3).y(0.2).width(0.3).height(0.5).build())
                .quality(FaceQualityDto.builder().detScore(0.99f).faceWidth(384).faceHeight(360).faceAreaRatio(0.15f).laplacianVar(150f).meanBrightness(120f).build())
                .build();
        when(faceServiceClient.checkFaceQuality(image)).thenReturn(mockResponse);

        FaceQualityCheckResponse result = faceEnrollmentService.checkQuality(employeeId, image);

        assertThat(result).isNotNull();
        assertThat(result.getImageWidth()).isEqualTo(1280);
        assertThat(result.getImageHeight()).isEqualTo(720);
        assertThat(result.getFaceBox().getX()).isEqualTo(0.3);
        assertThat(result.getQuality().getDetScore()).isEqualTo(0.99f);

        // Verification: faceServiceClient called once, NO transaction service, NO repo saves, NO revision changes
        verify(faceServiceClient, times(1)).checkFaceQuality(image);
        verifyNoInteractions(faceEnrollmentTransactionService);
        verify(employeeRepository, never()).save(any());
        assertThat(activeEmployee.getFaceRevision()).isEqualTo(0L);
    }

    @Test
    @DisplayName("checkQuality propagates upstream quality exceptions (NO_FACE, BLURRY, etc.) without altering state")
    void checkQuality_upstreamErrorPropagates() {
        MockMultipartFile image = new MockMultipartFile("image", "probe.jpg", "image/jpeg", new byte[]{1, 2, 3});
        when(faceProperties.getImage()).thenReturn(imageProperties);
        when(employeeRepository.findByIdAndDeletedAtIsNull(employeeId)).thenReturn(Optional.of(activeEmployee));

        when(faceServiceClient.checkFaceQuality(image)).thenThrow(new AppException(ErrorCode.NO_FACE));

        AppException ex = assertThrows(AppException.class, () ->
                faceEnrollmentService.checkQuality(employeeId, image)
        );
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.NO_FACE);

        verifyNoInteractions(faceEnrollmentTransactionService);
        verify(employeeRepository, never()).save(any());
    }
}
