package com.pandora.coreservice.config;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(FaceProperties.class)
public class FaceConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "faceRestClient")
    public RestClient faceRestClient(FaceProperties properties, RestClient.Builder restClientBuilder) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(properties.getService().getConnectTimeoutMillis()));
        requestFactory.setReadTimeout(Duration.ofMillis(properties.getService().getReadTimeoutMillis()));

        return restClientBuilder
                .baseUrl(properties.getService().getUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public MultipartConfigElement multipartConfigElement() {
        return new MultipartConfigElement("", 2 * 1024 * 1024L, 7 * 1024 * 1024L, 7 * 1024 * 1024);
    }
}
