package com.pandora.coreservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class FaceEmbedUpstreamResponse {

    private List<Float> embedding;
    private Integer dimension;
    private String modelId;
    private String modelVersion;
    private String preprocessingVersion;
    private FaceQualityDto quality;
}
