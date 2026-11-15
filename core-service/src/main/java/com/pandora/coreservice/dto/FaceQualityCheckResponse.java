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
public class FaceQualityCheckResponse {

    private Integer imageWidth;
    private Integer imageHeight;
    private FaceBoxDto faceBox;
    private FaceQualityDto quality;
}
