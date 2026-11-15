package com.pandora.coreservice.service;

import com.pandora.coreservice.config.FaceProperties;
import com.pandora.coreservice.dto.FaceDeleteRequest;
import com.pandora.coreservice.dto.FaceEmbeddingResult;
import com.pandora.coreservice.dto.FaceEnrollmentResponse;
import com.pandora.coreservice.entity.Employee;
import com.pandora.coreservice.entity.EmployeeFaceEnrollment;
import com.pandora.coreservice.exception.AppException;
import com.pandora.coreservice.exception.ErrorCode;
import com.pandora.coreservice.repo.EmployeeFaceEnrollmentRepository;
import com.pandora.coreservice.repo.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FaceEnrollmentService {

    public static final int REQUIRED_SAMPLE_COUNT = 3;
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/jpg",
            "image/png"
    );

    private final EmployeeRepository employeeRepository;
    private final EmployeeFaceEnrollmentRepository employeeFaceEnrollmentRepository;
    private final FaceServiceClient faceServiceClient;
    private final FaceProperties faceProperties;
    private final FaceEnrollmentTransactionService faceEnrollmentTransactionService;

    @Transactional(readOnly = true)
    public FaceEnrollmentResponse getStatus(UUID employeeId) {
        if (employeeId == null) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }

        Employee employee = employeeRepository.findByIdAndDeletedAtIsNull(employeeId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));

        Optional<EmployeeFaceEnrollment> enrollmentOpt = employeeFaceEnrollmentRepository.findByEmployeeId(employeeId);
        if (enrollmentOpt.isPresent()) {
            EmployeeFaceEnrollment enrollment = enrollmentOpt.get();
            return FaceEnrollmentResponse.builder()
                    .employeeId(employeeId)
                    .registered(true)
                    .sampleCount(enrollment.getSampleCount())
                    .revision(employee.getFaceRevision())
                    .enrolledAt(enrollment.getEnrolledAt())
                    .build();
        }

        return FaceEnrollmentResponse.builder()
                .employeeId(employeeId)
                .registered(false)
                .sampleCount(0)
                .revision(employee.getFaceRevision())
                .enrolledAt(null)
                .build();
    }

    public FaceEnrollmentResponse enroll(
            UUID employeeId,
            UUID adminId,
            List<MultipartFile> images,
            Long expectedRevision) {

        if (employeeId == null || adminId == null || expectedRevision == null || expectedRevision < 0) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        if (images == null || images.size() != REQUIRED_SAMPLE_COUNT) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }

        validateImages(images);

        Employee employee = employeeRepository.findByIdAndDeletedAtIsNull(employeeId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));

        if (!expectedRevision.equals(employee.getFaceRevision())) {
            throw new AppException(ErrorCode.STALE_REVISION);
        }

        List<FaceEmbeddingResult> embeddingResults = new ArrayList<>(images.size());
        for (MultipartFile file : images) {
            FaceEmbeddingResult result = faceServiceClient.embedEnrollmentImage(file);
            embeddingResults.add(result);
        }

        return faceEnrollmentTransactionService.executeEnrollment(
                employeeId,
                adminId,
                expectedRevision,
                embeddingResults
        );
    }

    public FaceEnrollmentResponse enroll(
            UUID employeeId,
            UUID adminId,
            MultipartFile[] images,
            Long expectedRevision) {
        List<MultipartFile> list = images != null ? Arrays.asList(images) : null;
        return enroll(employeeId, adminId, list, expectedRevision);
    }

    public FaceEnrollmentResponse delete(UUID employeeId, Long revision) {
        if (employeeId == null || revision == null || revision < 0) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        return faceEnrollmentTransactionService.executeDelete(
                employeeId,
                revision
        );
    }

    public FaceEnrollmentResponse delete(UUID employeeId, FaceDeleteRequest request) {
        if (request == null || request.getExpectedRevision() == null || request.getExpectedRevision() < 0) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        return delete(employeeId, request.getExpectedRevision());
    }

    private void validateImages(List<MultipartFile> images) {
        long maxBytes = 2 * 1024 * 1024L;
        if (faceProperties != null && faceProperties.getImage() != null
                && faceProperties.getImage().getMaxBytes() > 0) {
            maxBytes = faceProperties.getImage().getMaxBytes();
        }

        for (MultipartFile file : images) {
            if (file == null || file.isEmpty()) {
                throw new AppException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
            }
            if (file.getSize() > maxBytes) {
                throw new AppException(ErrorCode.UPLOAD_TOO_LARGE);
            }
            String contentType = file.getContentType();
            if (contentType == null || contentType.isBlank()) {
                throw new AppException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
            }
            String normalizedType = contentType.toLowerCase().split(";")[0].trim();
            if (!ALLOWED_CONTENT_TYPES.contains(normalizedType)) {
                throw new AppException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
            }
        }
    }
}
