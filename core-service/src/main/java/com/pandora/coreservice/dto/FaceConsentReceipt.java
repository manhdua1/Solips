package com.pandora.coreservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FaceConsentReceipt {

    private UUID employeeId;
    private UUID adminId;
    private Long revision;
    private String consentVersion;
    private Instant consentedAt;
    private Instant expiresAt;
}
