package com.pandora.coreservice.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ErrorCode {

    UNCATEGORIZED_EXCEPTION(9999, "Internal server error", HttpStatus.INTERNAL_SERVER_ERROR),
    INVALID_CREDENTIALS(1001, "Invalid credentials", HttpStatus.UNAUTHORIZED),
    UNAUTHENTICATED(1002, "Unauthenticated", HttpStatus.UNAUTHORIZED),
    ACCESS_DENIED(1003, "Access denied", HttpStatus.FORBIDDEN),
    INVALID_REQUEST(1004, "Malformed request", HttpStatus.BAD_REQUEST),
    RESOURCE_NOT_FOUND(1005, "Resource not found", HttpStatus.NOT_FOUND),
    DUPLICATE_RESOURCE(1006, "Resource already exists", HttpStatus.CONFLICT),
    LOCK_CONFLICT(1007, "Lock acquisition conflict; please retry", HttpStatus.CONFLICT),

    // Face enrollment & revision codes
    STALE_REVISION(1008, "Face revision conflict; please refresh", HttpStatus.CONFLICT),
    INVALID_CONSENT_VERSION(1009, "Consent version mismatch or outdated", HttpStatus.CONFLICT),
    UPLOAD_TOO_LARGE(1010, "Image or request payload exceeds limit", HttpStatus.PAYLOAD_TOO_LARGE),
    UNSUPPORTED_MEDIA_TYPE(1011, "Unsupported media type; expected JPEG or PNG", HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    NO_FACE(1012, "No face detected in image", HttpStatus.UNPROCESSABLE_ENTITY),
    MULTIPLE_FACES(1013, "Multiple faces detected in image", HttpStatus.UNPROCESSABLE_ENTITY),
    FACE_TOO_SMALL(1014, "Face is too small or too far away", HttpStatus.UNPROCESSABLE_ENTITY),
    IMAGE_BLURRY(1015, "Image is blurry", HttpStatus.UNPROCESSABLE_ENTITY),
    ILLUMINATION_POOR(1016, "Lighting is too dark or overexposed", HttpStatus.UNPROCESSABLE_ENTITY),
    INVALID_CONSENT(1017, "Face consent is invalid, expired, or not accepted", HttpStatus.UNPROCESSABLE_ENTITY),
    UPSTREAM_FACE_SERVICE_ERROR(1018, "Face service upstream error", HttpStatus.BAD_GATEWAY),
    FACE_SERVICE_UNAVAILABLE(1019, "Face service is unavailable", HttpStatus.SERVICE_UNAVAILABLE),
    FACE_SERVICE_TIMEOUT(1020, "Face service request timed out", HttpStatus.GATEWAY_TIMEOUT);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;
}
