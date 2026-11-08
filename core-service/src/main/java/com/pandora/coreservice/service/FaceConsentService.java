package com.pandora.coreservice.service;

import com.pandora.coreservice.config.FaceProperties;
import com.pandora.coreservice.dto.FaceConsentReceipt;
import com.pandora.coreservice.dto.FaceConsentRequest;
import com.pandora.coreservice.dto.FaceConsentResponse;
import com.pandora.coreservice.exception.AppException;
import com.pandora.coreservice.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FaceConsentService {

    public static final String DEFAULT_CONSENT_VERSION = "face-enrollment-v1";
    public static final String DEFAULT_CONSENT_TEXT = "I consent to the collection and processing of my facial biometric templates for attendance verification. Facial image samples are converted to numeric embeddings and raw images are not permanently stored.";
    public static final String FACE_CONSENT_SECRET_ENV = "FACE_CONSENT_SECRET";
    public static final String FACE_CONSENT_SECRET_PROP = "face.consent.secret";
    public static final int MIN_KEY_BYTES = 32;

    private final FaceProperties faceProperties;
    private final Environment environment;

    private volatile JwtEncoder jwtEncoder;
    private volatile JwtDecoder jwtDecoder;

    public String getConsentVersion() {
        if (faceProperties != null && faceProperties.getConsent() != null
                && faceProperties.getConsent().getVersion() != null
                && !faceProperties.getConsent().getVersion().isBlank()) {
            return faceProperties.getConsent().getVersion();
        }
        return DEFAULT_CONSENT_VERSION;
    }

    public String getConsentText() {
        return DEFAULT_CONSENT_TEXT;
    }

    public FaceConsentResponse issueConsent(UUID employeeId, UUID adminId, Long revision, FaceConsentRequest request) {
        if (employeeId == null || adminId == null) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        if (request == null || !Boolean.TRUE.equals(request.getAccepted())) {
            throw new AppException(ErrorCode.INVALID_CONSENT);
        }

        String expectedVersion = getConsentVersion();
        if (request.getConsentVersion() != null && !request.getConsentVersion().isBlank()
                && !expectedVersion.equals(request.getConsentVersion())) {
            throw new AppException(ErrorCode.INVALID_CONSENT_VERSION);
        }

        long currentRevision = revision != null ? revision : 0L;
        if (request.getExpectedRevision() != null && !request.getExpectedRevision().equals(currentRevision)) {
            throw new AppException(ErrorCode.STALE_REVISION);
        }

        long expiryMinutes = 10L;
        if (faceProperties != null && faceProperties.getConsent() != null
                && faceProperties.getConsent().getExpiryMinutes() > 0) {
            expiryMinutes = faceProperties.getConsent().getExpiryMinutes();
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = now.plus(Duration.ofMinutes(expiryMinutes));

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(employeeId.toString())
                .claim("adminId", adminId.toString())
                .claim("revision", currentRevision)
                .claim("consentVersion", expectedVersion)
                .issuedAt(now)
                .expiresAt(expiresAt)
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = getOrCreateJwtEncoder().encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        return FaceConsentResponse.builder()
                .consentToken(token)
                .consentVersion(expectedVersion)
                .consentedAt(now)
                .expiresAt(expiresAt)
                .build();
    }

    public FaceConsentReceipt verifyConsentReceipt(String token, UUID expectedEmployeeId, UUID expectedAdminId, Long expectedRevision) {
        if (token == null || token.isBlank()) {
            throw new AppException(ErrorCode.INVALID_CONSENT);
        }

        Jwt jwt;
        try {
            jwt = getOrCreateJwtDecoder().decode(token.trim());
        } catch (JwtException e) {
            throw new AppException(ErrorCode.INVALID_CONSENT);
        }

        String sub = jwt.getSubject();
        Object adminClaim = jwt.getClaim("adminId");
        Object revClaim = jwt.getClaim("revision");
        Object verClaim = jwt.getClaim("consentVersion");

        if (sub == null || adminClaim == null || revClaim == null) {
            throw new AppException(ErrorCode.INVALID_CONSENT);
        }

        UUID employeeId;
        UUID adminId;
        Long revision;
        try {
            employeeId = UUID.fromString(sub);
            adminId = UUID.fromString(adminClaim.toString());
            revision = Long.parseLong(revClaim.toString());
        } catch (Exception e) {
            throw new AppException(ErrorCode.INVALID_CONSENT);
        }

        String tokenConsentVersion = verClaim != null ? verClaim.toString() : "";
        if (!getConsentVersion().equals(tokenConsentVersion)) {
            throw new AppException(ErrorCode.INVALID_CONSENT_VERSION);
        }

        if (expectedEmployeeId != null && !expectedEmployeeId.equals(employeeId)) {
            throw new AppException(ErrorCode.INVALID_CONSENT);
        }
        if (expectedAdminId != null && !expectedAdminId.equals(adminId)) {
            throw new AppException(ErrorCode.INVALID_CONSENT);
        }
        if (expectedRevision != null && !expectedRevision.equals(revision)) {
            throw new AppException(ErrorCode.STALE_REVISION);
        }

        Instant consentedAt = jwt.getIssuedAt();
        Instant expiresAt = jwt.getExpiresAt();

        return FaceConsentReceipt.builder()
                .employeeId(employeeId)
                .adminId(adminId)
                .revision(revision)
                .consentVersion(tokenConsentVersion)
                .consentedAt(consentedAt)
                .expiresAt(expiresAt)
                .build();
    }

    public FaceConsentReceipt verifyConsentReceipt(String token) {
        return verifyConsentReceipt(token, null, null, null);
    }

    private JwtEncoder getOrCreateJwtEncoder() {
        if (this.jwtEncoder == null) {
            synchronized (this) {
                if (this.jwtEncoder == null) {
                    initJwtCrypto();
                }
            }
        }
        return this.jwtEncoder;
    }

    private JwtDecoder getOrCreateJwtDecoder() {
        if (this.jwtDecoder == null) {
            synchronized (this) {
                if (this.jwtDecoder == null) {
                    initJwtCrypto();
                }
            }
        }
        return this.jwtDecoder;
    }

    private void initJwtCrypto() {
        SecretKey secretKey = resolveSecretKey();
        this.jwtEncoder = NimbusJwtEncoder.withSecretKey(secretKey).algorithm(MacAlgorithm.HS256).build();
        this.jwtDecoder = NimbusJwtDecoder.withSecretKey(secretKey).macAlgorithm(MacAlgorithm.HS256).build();
    }

    private SecretKey resolveSecretKey() {
        String key = null;
        if (faceProperties != null && faceProperties.getConsent() != null) {
            key = faceProperties.getConsent().getSecret();
        }
        if (key == null || key.isBlank()) {
            if (environment != null) {
                key = environment.getProperty(FACE_CONSENT_SECRET_ENV);
                if (key == null || key.isBlank()) {
                    key = environment.getProperty(FACE_CONSENT_SECRET_PROP);
                }
            }
        }
        if (key == null || key.isBlank()) {
            key = System.getenv(FACE_CONSENT_SECRET_ENV);
        }

        if (key == null || key.isBlank()) {
            throw new IllegalStateException("Face consent signing secret is missing: configure "
                    + FACE_CONSENT_SECRET_ENV + " or " + FACE_CONSENT_SECRET_PROP);
        }

        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(key.trim());
            if (decoded.length < MIN_KEY_BYTES) {
                decoded = key.trim().getBytes(StandardCharsets.UTF_8);
            }
        } catch (IllegalArgumentException e) {
            decoded = key.trim().getBytes(StandardCharsets.UTF_8);
        }

        if (decoded.length < MIN_KEY_BYTES) {
            throw new IllegalStateException("Face consent signing secret must be at least "
                    + MIN_KEY_BYTES + " bytes (256 bits)");
        }

        return new SecretKeySpec(decoded, "HmacSHA256");
    }
}
