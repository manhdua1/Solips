package com.pandora.coreservice.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class KioskRecognitionResult {
    private final boolean success;
    private final String reason;
}
