package com.pandora.coreservice.controller;

import com.pandora.coreservice.dto.ApiResponse;
import com.pandora.coreservice.dto.FaceDeleteRequest;
import com.pandora.coreservice.dto.FaceEnrollmentResponse;
import com.pandora.coreservice.exception.AppException;
import com.pandora.coreservice.exception.ErrorCode;
import com.pandora.coreservice.service.FaceEnrollmentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.util.WebUtils;

import java.util.Iterator;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/employees/{id}/face")
@RequiredArgsConstructor
@Validated
public class FaceEnrollmentController {

    private final FaceEnrollmentService faceEnrollmentService;

    @GetMapping
    public ResponseEntity<ApiResponse<FaceEnrollmentResponse>> getStatus(
            @PathVariable("id") UUID id) {
        FaceEnrollmentResponse response = faceEnrollmentService.getStatus(id);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(response));
    }

    @PutMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<FaceEnrollmentResponse>> enroll(
            @PathVariable("id") UUID id,
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication,
            @RequestParam("images") List<MultipartFile> images,
            @RequestParam(name = "expectedRevision", required = true) @NotNull @PositiveOrZero Long expectedRevision,
            HttpServletRequest request) {

        MultipartHttpServletRequest multipartRequest = WebUtils.getNativeRequest(request, MultipartHttpServletRequest.class);
        if (multipartRequest == null && request instanceof MultipartHttpServletRequest mr) {
            multipartRequest = mr;
        }
        if (multipartRequest != null) {
            for (Iterator<String> it = multipartRequest.getFileNames(); it.hasNext(); ) {
                String fieldName = it.next();
                if (!"images".equals(fieldName)) {
                    throw new AppException(ErrorCode.INVALID_REQUEST);
                }
            }
        }

        UUID adminId = extractActorId(jwt, authentication);
        FaceEnrollmentResponse response = faceEnrollmentService.enroll(
                id, adminId, images, expectedRevision
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(response));
    }

    @DeleteMapping
    public ResponseEntity<ApiResponse<FaceEnrollmentResponse>> delete(
            @PathVariable("id") UUID id,
            @Valid @RequestBody(required = true) FaceDeleteRequest request) {
        FaceEnrollmentResponse response = faceEnrollmentService.delete(id, request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(response));
    }

    private UUID extractActorId(Jwt jwt, Authentication authentication) {
        String subject = null;
        if (jwt != null && jwt.getSubject() != null && !jwt.getSubject().isBlank()) {
            subject = jwt.getSubject();
        } else if (authentication != null) {
            if (authentication.getPrincipal() instanceof Jwt principalJwt
                    && principalJwt.getSubject() != null
                    && !principalJwt.getSubject().isBlank()) {
                subject = principalJwt.getSubject();
            } else if (authentication.getName() != null && !authentication.getName().isBlank()) {
                subject = authentication.getName();
            }
        }
        if (subject == null) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        try {
            return UUID.fromString(subject.trim());
        } catch (IllegalArgumentException e) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
    }
}
