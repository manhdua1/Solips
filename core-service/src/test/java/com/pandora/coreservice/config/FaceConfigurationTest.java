package com.pandora.coreservice.config;

import com.pandora.coreservice.service.FaceServiceClient;
import jakarta.servlet.MultipartConfigElement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

class FaceConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RestClientAutoConfiguration.class))
            .withUserConfiguration(FaceConfiguration.class);

    @Test
    @DisplayName("Context fails when RestClient.Builder auto-configuration is missing (root-cause reproduction)")
    void failsWhenRestClientAutoConfigurationMissing() {
        new ApplicationContextRunner()
                .withUserConfiguration(FaceConfiguration.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(NoSuchBeanDefinitionException.class)
                            .hasStackTraceContaining("org.springframework.web.client.RestClient$Builder");
                });
    }

    @Test
    @DisplayName("Auto-configured RestClient.Builder is available and injected into faceRestClient bean")
    void autoConfiguredRestClientBuilderProvidesFaceRestClient() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(RestClient.Builder.class);
            assertThat(context).hasBean("faceRestClient");
            assertThat(context).getBean("faceRestClient").isInstanceOf(RestClient.class);
            assertThat(context).hasSingleBean(FaceProperties.class);
            assertThat(context).hasSingleBean(MultipartConfigElement.class);
        });
    }

    @Test
    @DisplayName("FaceConfiguration respects customized service URL and timeouts")
    void customFacePropertiesApplied() {
        contextRunner
                .withPropertyValues(
                        "face.service.url=https://face.internal.example.com",
                        "face.service.connect-timeout-millis=5000",
                        "face.service.read-timeout-millis=30000"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(FaceProperties.class);
                    FaceProperties properties = context.getBean(FaceProperties.class);
                    assertThat(properties.getService().getUrl()).isEqualTo("https://face.internal.example.com");
                    assertThat(properties.getService().getConnectTimeoutMillis()).isEqualTo(5000);
                    assertThat(properties.getService().getReadTimeoutMillis()).isEqualTo(30000);
                    assertThat(context).hasBean("faceRestClient");
                });
    }

    @Test
    @DisplayName("FaceServiceClient wiring succeeds when auto-configuration supplies RestClient.Builder")
    void faceServiceClientWiringSucceeds() {
        contextRunner
                .withUserConfiguration(FaceServiceClient.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(FaceServiceClient.class);
                    assertThat(context).hasBean("faceRestClient");
                });
    }

    @Test
    @DisplayName("User-defined faceRestClient bean takes precedence over default (@ConditionalOnMissingBean)")
    void customFaceRestClientTakesPrecedence() {
        RestClient customClient = RestClient.builder().baseUrl("http://custom-url:9999").build();

        contextRunner
                .withBean("faceRestClient", RestClient.class, () -> customClient)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("faceRestClient")).isSameAs(customClient);
                });
    }
}
