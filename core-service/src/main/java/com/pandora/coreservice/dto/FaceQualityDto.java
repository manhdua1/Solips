package com.pandora.coreservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
@JsonIgnoreProperties(ignoreUnknown = true)
public class FaceQualityDto {

    private Float detScore;
    private Integer faceWidth;
    private Integer faceHeight;
    private Float faceAreaRatio;
    private Float laplacianVar;
    private Float meanBrightness;
}
