package com.pandora.coreservice.controller;

import com.pandora.coreservice.dto.ApiResponse;
import com.pandora.coreservice.dto.KioskRecognitionResult;
import com.pandora.coreservice.exception.AppException;
import com.pandora.coreservice.exception.ErrorCode;
import com.pandora.coreservice.service.KioskRecognitionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.util.WebUtils;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/kiosk")
@RequiredArgsConstructor
public class KioskController {

    private final KioskRecognitionService kioskRecognitionService;

    @PostMapping(path = "/recognize", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<KioskRecognitionResult>> recognize(
            @RequestParam("image") MultipartFile image,
            HttpServletRequest request) {

        MultipartHttpServletRequest multipartRequest = WebUtils.getNativeRequest(request, MultipartHttpServletRequest.class);
        if (multipartRequest == null && request instanceof MultipartHttpServletRequest mr) {
            multipartRequest = mr;
        }

        if (multipartRequest != null) {
            Set<String> fileNames = multipartRequest.getFileMap().keySet();
            if (fileNames.size() != 1 || !fileNames.contains("image")) {
                throw new AppException(ErrorCode.INVALID_REQUEST);
            }
            List<MultipartFile> imageFiles = multipartRequest.getFiles("image");
            if (imageFiles == null || imageFiles.size() != 1) {
                throw new AppException(ErrorCode.INVALID_REQUEST);
            }
        }

        if (request.getParameterMap() != null && !request.getParameterMap().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }

        KioskRecognitionResult result = kioskRecognitionService.recognize(image);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(result));
    }
}
