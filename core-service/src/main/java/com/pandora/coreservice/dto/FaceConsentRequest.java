package com.pandora.coreservice.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FaceConsentRequest {

    @NotNull
    private Boolean accepted;

    private String consentVersion;

    private Long expectedRevision;
}
