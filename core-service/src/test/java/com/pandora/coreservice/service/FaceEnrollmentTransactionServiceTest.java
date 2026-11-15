package com.pandora.coreservice.service;

import com.pandora.coreservice.dto.FaceEmbeddingResult;
import com.pandora.coreservice.dto.FaceEnrollmentResponse;
import com.pandora.coreservice.dto.FaceQualityDto;
import com.pandora.coreservice.entity.Employee;
import com.pandora.coreservice.entity.EmployeeFaceEnrollment;
import com.pandora.coreservice.entity.EmployeeStatus;
import com.pandora.coreservice.exception.AppException;
import com.pandora.coreservice.exception.ErrorCode;
import com.pandora.coreservice.repo.EmployeeFaceEnrollmentRepository;
import com.pandora.coreservice.repo.EmployeeFaceSampleRepository;
import com.pandora.coreservice.repo.EmployeeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FaceEnrollmentTransactionServiceTest {

    @Mock
    private EmployeeRepository employeeRepository;

    @Mock
    private EmployeeFaceEnrollmentRepository employeeFaceEnrollmentRepository;

    @Mock
    private EmployeeFaceSampleRepository employeeFaceSampleRepository;

    @InjectMocks
    private FaceEnrollmentTransactionService transactionService;

    private UUID employeeId;
    private UUID adminId;
    private Employee activeEmployee;

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
    }

    private List<FaceEmbeddingResult> createValidThreeResults() {
        List<FaceEmbeddingResult> list = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            list.add(FaceEmbeddingResult.builder()
                    .dimension(512)
                    .embedding(Collections.nCopies(512, 1.0f / (float) Math.sqrt(512)))
                    .embeddingBytes(new byte[2048])
                    .modelId("buffalo_l")
                    .modelVersion("1.0")
                    .preprocessingVersion("v1")
                    .quality(FaceQualityDto.builder()
                            .detScore(0.95f)
                            .faceWidth(200)
                            .faceHeight(200)
                            .faceAreaRatio(0.15f)
                            .laplacianVar(120.0f)
                            .meanBrightness(110.0f)
                            .build())
                    .build());
        }
        return list;
    }

    // --- ENROLL: First Enrollment Success ---

    @Test
    @DisplayName("First enrollment creates enrollment and samples, flushes, and increments revision")
    void executeEnrollment_firstEnrollment_success() {
        List<FaceEmbeddingResult> embeddingResults = createValidThreeResults();

        when(employeeRepository.findActiveByIdForUpdate(employeeId)).thenReturn(Optional.of(activeEmployee));
        when(employeeFaceEnrollmentRepository.findByEmployeeId(employeeId)).thenReturn(Optional.empty());
        when(employeeFaceEnrollmentRepository.save(any(EmployeeFaceEnrollment.class))).thenAnswer(invocation -> {
            EmployeeFaceEnrollment enrollment = invocation.getArgument(0);
            enrollment.setId(UUID.randomUUID());
            return enrollment;
        });

        FaceEnrollmentResponse response = transactionService.executeEnrollment(
                employeeId, adminId, 0L, embeddingResults
        );

        assertThat(response).isNotNull();
        assertThat(response.isRegistered()).isTrue();
        assertThat(response.getEmployeeId()).isEqualTo(employeeId);
        assertThat(response.getSampleCount()).isEqualTo(3);
        assertThat(response.getRevision()).isEqualTo(1L);
        assertThat(response.getEnrolledAt()).isNotNull();

        assertThat(activeEmployee.getFaceRevision()).isEqualTo(1L);
        verify(employeeRepository).save(activeEmployee);
        verify(employeeFaceEnrollmentRepository).save(any(EmployeeFaceEnrollment.class));
        verify(employeeFaceEnrollmentRepository).flush();
        verify(employeeFaceSampleRepository, never()).deleteByEnrollmentId(any());
    }

    // --- ENROLL: Re-enrollment Deletes and Flushes Before Insert ---

    @Test
    @DisplayName("Re-enrollment deletes old samples and enrollment, flushes, then inserts new enrollment")
    void executeEnrollment_reEnrollment_deletesAndFlushesBeforeInsert() {
        List<FaceEmbeddingResult> embeddingResults = createValidThreeResults();
        UUID oldEnrollmentId = UUID.randomUUID();

        EmployeeFaceEnrollment existingEnrollment = new EmployeeFaceEnrollment();
        existingEnrollment.setId(oldEnrollmentId);
        existingEnrollment.setEmployeeId(employeeId);
        existingEnrollment.setEnrolledAt(Instant.now());

        activeEmployee.setFaceRevision(1L);

        when(employeeRepository.findActiveByIdForUpdate(employeeId)).thenReturn(Optional.of(activeEmployee));
        when(employeeFaceEnrollmentRepository.findByEmployeeId(employeeId)).thenReturn(Optional.of(existingEnrollment));
        when(employeeFaceEnrollmentRepository.save(any(EmployeeFaceEnrollment.class))).thenAnswer(invocation -> {
            EmployeeFaceEnrollment enrollment = invocation.getArgument(0);
            enrollment.setId(UUID.randomUUID());
            return enrollment;
        });

        FaceEnrollmentResponse response = transactionService.executeEnrollment(
                employeeId, adminId, 1L, embeddingResults
        );

        assertThat(response).isNotNull();
        assertThat(response.isRegistered()).isTrue();
        assertThat(response.getRevision()).isEqualTo(2L);

        // Verify strict deletion flush order before new insert to prevent unique constraint conflict
        InOrder inOrder = inOrder(employeeFaceSampleRepository, employeeFaceEnrollmentRepository);
        inOrder.verify(employeeFaceSampleRepository).deleteByEnrollmentId(oldEnrollmentId);
        inOrder.verify(employeeFaceSampleRepository).flush();
        inOrder.verify(employeeFaceEnrollmentRepository).delete(existingEnrollment);
        inOrder.verify(employeeFaceEnrollmentRepository).flush();
        inOrder.verify(employeeFaceEnrollmentRepository).save(any(EmployeeFaceEnrollment.class));
        inOrder.verify(employeeFaceEnrollmentRepository).flush();
    }

    // --- ENROLL: Revision Conflict & Not Found ---

    @Test
    @DisplayName("Enrollment with stale revision throws STALE_REVISION conflict")
    void executeEnrollment_staleRevision_throwsStaleRevision() {
        activeEmployee.setFaceRevision(2L);
        when(employeeRepository.findActiveByIdForUpdate(employeeId)).thenReturn(Optional.of(activeEmployee));

        assertThatThrownBy(() -> transactionService.executeEnrollment(
                employeeId, adminId, 0L, createValidThreeResults()
        ))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode()).isEqualTo(ErrorCode.STALE_REVISION));

        verify(employeeFaceEnrollmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("Enrollment for non-existent employee throws RESOURCE_NOT_FOUND")
    void executeEnrollment_employeeNotFound_throwsResourceNotFound() {
        when(employeeRepository.findActiveByIdForUpdate(employeeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.executeEnrollment(
                employeeId, adminId, 0L, createValidThreeResults()
        ))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    // --- ENROLL: Embedding Validation ---

    @Test
    @DisplayName("Enrollment with invalid embedding dimensions or byte size throws UPSTREAM_FACE_SERVICE_ERROR")
    void executeEnrollment_invalidEmbedding_throwsUpstreamError() {
        List<FaceEmbeddingResult> list = createValidThreeResults();
        list.set(0, FaceEmbeddingResult.builder()
                .dimension(256) // Invalid dimension != 512
                .embeddingBytes(new byte[1024])
                .modelId("buffalo_l")
                .modelVersion("1.0")
                .preprocessingVersion("v1")
                .build());

        assertThatThrownBy(() -> transactionService.executeEnrollment(employeeId, adminId, 0L, list))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode()).isEqualTo(ErrorCode.UPSTREAM_FACE_SERVICE_ERROR));
    }

    // --- DELETE: Existing Enrollment ---

    @Test
    @DisplayName("Delete removes samples and enrollment with flush, then increments revision")
    void executeDelete_existingEnrollment_deletesAndFlushes() {
        UUID oldEnrollmentId = UUID.randomUUID();
        EmployeeFaceEnrollment existingEnrollment = new EmployeeFaceEnrollment();
        existingEnrollment.setId(oldEnrollmentId);
        existingEnrollment.setEmployeeId(employeeId);

        when(employeeRepository.findActiveByIdForUpdate(employeeId)).thenReturn(Optional.of(activeEmployee));
        when(employeeFaceEnrollmentRepository.findByEmployeeId(employeeId)).thenReturn(Optional.of(existingEnrollment));

        FaceEnrollmentResponse response = transactionService.executeDelete(employeeId, 0L);

        assertThat(response).isNotNull();
        assertThat(response.isRegistered()).isFalse();
        assertThat(response.getSampleCount()).isEqualTo(0);
        assertThat(response.getRevision()).isEqualTo(1L);

        InOrder inOrder = inOrder(employeeFaceSampleRepository, employeeFaceEnrollmentRepository);
        inOrder.verify(employeeFaceSampleRepository).deleteByEnrollmentId(oldEnrollmentId);
        inOrder.verify(employeeFaceSampleRepository).flush();
        inOrder.verify(employeeFaceEnrollmentRepository).delete(existingEnrollment);
        inOrder.verify(employeeFaceEnrollmentRepository).flush();

        verify(employeeRepository).save(activeEmployee);
    }

    @Test
    @DisplayName("Delete with stale revision throws STALE_REVISION")
    void executeDelete_staleRevision_throwsStaleRevision() {
        activeEmployee.setFaceRevision(3L);
        when(employeeRepository.findActiveByIdForUpdate(employeeId)).thenReturn(Optional.of(activeEmployee));

        assertThatThrownBy(() -> transactionService.executeDelete(employeeId, 1L))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode()).isEqualTo(ErrorCode.STALE_REVISION));
    }
}
