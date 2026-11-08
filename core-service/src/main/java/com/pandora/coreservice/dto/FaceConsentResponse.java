package com.pandora.coreservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FaceConsentResponse {

    private String consentToken;
    private String consentVersion;
    private Instant consentedAt;
    private Instant expiresAt;
}
