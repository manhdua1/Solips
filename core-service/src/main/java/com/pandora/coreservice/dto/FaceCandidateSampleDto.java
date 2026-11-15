package com.pandora.coreservice.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.UUID;

@Getter
@AllArgsConstructor
public class FaceCandidateSampleDto {
    private final UUID employeeId;
    private final byte[] embedding;
    private final int dimension;
    private final String modelId;
    private final String modelVersion;
    private final String preprocessingVersion;
}
