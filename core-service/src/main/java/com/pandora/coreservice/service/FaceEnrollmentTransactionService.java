package com.pandora.coreservice.service;

import com.pandora.coreservice.dto.FaceConsentReceipt;
import com.pandora.coreservice.dto.FaceEmbeddingResult;
import com.pandora.coreservice.dto.FaceEnrollmentResponse;
import com.pandora.coreservice.entity.Employee;
import com.pandora.coreservice.entity.EmployeeFaceEnrollment;
import com.pandora.coreservice.entity.EmployeeFaceSample;
import com.pandora.coreservice.exception.AppException;
import com.pandora.coreservice.exception.ErrorCode;
import com.pandora.coreservice.repo.EmployeeFaceEnrollmentRepository;
import com.pandora.coreservice.repo.EmployeeFaceSampleRepository;
import com.pandora.coreservice.repo.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FaceEnrollmentTransactionService {

    private final EmployeeRepository employeeRepository;
    private final EmployeeFaceEnrollmentRepository employeeFaceEnrollmentRepository;
    private final EmployeeFaceSampleRepository employeeFaceSampleRepository;
    private final FaceConsentService faceConsentService;

    @Transactional
    public FaceEnrollmentResponse executeEnrollment(
            UUID employeeId,
            UUID adminId,
            String consentToken,
            Long expectedRevision,
            List<FaceEmbeddingResult> embeddingResults,
            String canonicalConsentText,
            String canonicalConsentVersion) {

        validateEmbeddingResults(embeddingResults);

        if (expectedRevision == null || expectedRevision < 0) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }

        Employee employee = employeeRepository.findActiveByIdForUpdate(employeeId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));

        if (!expectedRevision.equals(employee.getFaceRevision())) {
            throw new AppException(ErrorCode.STALE_REVISION);
        }

        FaceConsentReceipt receipt = faceConsentService.verifyConsentReceipt(
                consentToken, employeeId, adminId, employee.getFaceRevision()
        );

        Optional<EmployeeFaceEnrollment> existingOpt = employeeFaceEnrollmentRepository.findByEmployeeId(employeeId);
        if (existingOpt.isPresent()) {
            EmployeeFaceEnrollment existing = existingOpt.get();
            employeeFaceSampleRepository.deleteByEnrollmentId(existing.getId());
            employeeFaceSampleRepository.flush();
            employeeFaceEnrollmentRepository.delete(existing);
            employeeFaceEnrollmentRepository.flush();
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        EmployeeFaceEnrollment enrollment = new EmployeeFaceEnrollment();
        enrollment.setEmployeeId(employeeId);
        enrollment.setEnrolledAt(now);
        enrollment.setConsentedAt(receipt.getConsentedAt());
        enrollment.setConsentedByAdminId(receipt.getAdminId());
        enrollment.setConsentVersion(receipt.getConsentVersion());
        enrollment.setConsentText(canonicalConsentText != null ? canonicalConsentText : faceConsentService.getConsentText());

        FaceEmbeddingResult first = embeddingResults.get(0);
        String modelId = first.getModelId();
        String modelVersion = first.getModelVersion();
        String preprocessingVersion = first.getPreprocessingVersion();

        enrollment.setModelId(modelId);
        enrollment.setModelVersion(modelVersion);
        enrollment.setPreprocessingVersion(preprocessingVersion);
        enrollment.setSampleCount(embeddingResults.size());
        enrollment.setUpdatedAt(now);

        for (int i = 0; i < embeddingResults.size(); i++) {
            FaceEmbeddingResult res = embeddingResults.get(i);
            EmployeeFaceSample sample = new EmployeeFaceSample();
            sample.setEnrollment(enrollment);
            sample.setSlot(i);
            sample.setEmbedding(res.getEmbeddingBytes());
            sample.setDimension(res.getDimension());
            sample.setModelId(res.getModelId());
            sample.setModelVersion(res.getModelVersion());
            sample.setPreprocessingVersion(res.getPreprocessingVersion());

            if (res.getQuality() != null) {
                sample.setDetScore(res.getQuality().getDetScore());
                sample.setFaceWidth(res.getQuality().getFaceWidth());
                sample.setFaceHeight(res.getQuality().getFaceHeight());
                sample.setFaceAreaRatio(res.getQuality().getFaceAreaRatio());
                sample.setLaplacianVar(res.getQuality().getLaplacianVar());
                sample.setMeanBrightness(res.getQuality().getMeanBrightness());
            }
            sample.setCreatedAt(now);
            enrollment.getSamples().add(sample);
        }

        EmployeeFaceEnrollment saved = employeeFaceEnrollmentRepository.save(enrollment);
        employeeFaceEnrollmentRepository.flush();

        employee.setFaceRevision(employee.getFaceRevision() + 1);
        employeeRepository.save(employee);

        return FaceEnrollmentResponse.builder()
                .employeeId(employeeId)
                .registered(true)
                .sampleCount(saved.getSampleCount())
                .revision(employee.getFaceRevision())
                .consentVersion(canonicalConsentVersion != null ? canonicalConsentVersion : faceConsentService.getConsentVersion())
                .consentText(canonicalConsentText != null ? canonicalConsentText : faceConsentService.getConsentText())
                .enrolledAt(saved.getEnrolledAt())
                .consentedAt(saved.getConsentedAt())
                .consentedByAdminId(saved.getConsentedByAdminId())
                .acceptedConsentVersion(saved.getConsentVersion())
                .build();
    }

    @Transactional
    public FaceEnrollmentResponse executeDelete(
            UUID employeeId,
            Long expectedRevision,
            String canonicalConsentText,
            String canonicalConsentVersion) {

        if (expectedRevision == null || expectedRevision < 0) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }

        Employee employee = employeeRepository.findActiveByIdForUpdate(employeeId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));

        if (!expectedRevision.equals(employee.getFaceRevision())) {
            throw new AppException(ErrorCode.STALE_REVISION);
        }

        Optional<EmployeeFaceEnrollment> existingOpt = employeeFaceEnrollmentRepository.findByEmployeeId(employeeId);
        if (existingOpt.isPresent()) {
            EmployeeFaceEnrollment existing = existingOpt.get();
            employeeFaceSampleRepository.deleteByEnrollmentId(existing.getId());
            employeeFaceSampleRepository.flush();
            employeeFaceEnrollmentRepository.delete(existing);
            employeeFaceEnrollmentRepository.flush();
        }

        employee.setFaceRevision(employee.getFaceRevision() + 1);
        employeeRepository.save(employee);

        return FaceEnrollmentResponse.builder()
                .employeeId(employeeId)
                .registered(false)
                .sampleCount(0)
                .revision(employee.getFaceRevision())
                .consentVersion(canonicalConsentVersion != null ? canonicalConsentVersion : faceConsentService.getConsentVersion())
                .consentText(canonicalConsentText != null ? canonicalConsentText : faceConsentService.getConsentText())
                .enrolledAt(null)
                .consentedAt(null)
                .consentedByAdminId(null)
                .acceptedConsentVersion(null)
                .build();
    }

    private void validateEmbeddingResults(List<FaceEmbeddingResult> embeddingResults) {
        if (embeddingResults == null || embeddingResults.size() != 3) {
            throw new AppException(ErrorCode.UPSTREAM_FACE_SERVICE_ERROR);
        }

        FaceEmbeddingResult first = embeddingResults.get(0);
        if (first == null) {
            throw new AppException(ErrorCode.UPSTREAM_FACE_SERVICE_ERROR);
        }

        String modelId = first.getModelId();
        String modelVersion = first.getModelVersion();
        String preprocessingVersion = first.getPreprocessingVersion();

        if (modelId == null || modelId.isBlank()
                || modelVersion == null || modelVersion.isBlank()
                || preprocessingVersion == null || preprocessingVersion.isBlank()) {
            throw new AppException(ErrorCode.UPSTREAM_FACE_SERVICE_ERROR);
        }

        for (FaceEmbeddingResult res : embeddingResults) {
            if (res == null) {
                throw new AppException(ErrorCode.UPSTREAM_FACE_SERVICE_ERROR);
            }
            if (res.getDimension() != 512) {
                throw new AppException(ErrorCode.UPSTREAM_FACE_SERVICE_ERROR);
            }
            byte[] bytes = res.getEmbeddingBytes();
            if (bytes == null || bytes.length != 2048) {
                throw new AppException(ErrorCode.UPSTREAM_FACE_SERVICE_ERROR);
            }
            if (res.getModelId() == null || res.getModelId().isBlank() || !modelId.equals(res.getModelId())) {
                throw new AppException(ErrorCode.UPSTREAM_FACE_SERVICE_ERROR);
            }
            if (res.getModelVersion() == null || res.getModelVersion().isBlank() || !modelVersion.equals(res.getModelVersion())) {
                throw new AppException(ErrorCode.UPSTREAM_FACE_SERVICE_ERROR);
            }
            if (res.getPreprocessingVersion() == null || res.getPreprocessingVersion().isBlank() || !preprocessingVersion.equals(res.getPreprocessingVersion())) {
                throw new AppException(ErrorCode.UPSTREAM_FACE_SERVICE_ERROR);
            }
        }
    }
}
