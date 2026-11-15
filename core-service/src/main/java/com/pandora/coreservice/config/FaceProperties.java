package com.pandora.coreservice.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "face")
public class FaceProperties {

    private Service service = new Service();
    private Image image = new Image();

    @Getter
    @Setter
    public static class Service {
        private String url = "http://localhost:8001";
        private String secret = "";
        private int connectTimeoutMillis = 3000;
        private int readTimeoutMillis = 15000;
        private String expectedModelId = "buffalo_l";
        private String expectedModelVersion = "";
        private String expectedPreprocessingVersion = "enrollment-v1";
    }

    @Getter
    @Setter
    public static class Image {
        private long maxBytes = 2 * 1024 * 1024L; // 2 MiB
    }
}
