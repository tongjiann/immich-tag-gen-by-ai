package com.xiwang.phototagautogen.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiwang.phototagautogen.config.OllamaProperties;
import com.xiwang.phototagautogen.config.OpenAiProperties;
import com.xiwang.phototagautogen.config.ProcessingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class VisionModelClientConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules())
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void 未配置提供方时应默认启用Ollama客户端() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(VisionModelClient.class);
            assertThat(context).hasSingleBean(OllamaHttpClient.class);
            assertThat(context).doesNotHaveBean(OpenAiCompatibleHttpClient.class);
        });
    }

    @Test
    void 配置OpenAI提供方时应仅启用OpenAI兼容客户端() {
        contextRunner
                .withPropertyValues("vision.provider=openai", "openai.model=vision-model")
                .run(context -> {
                    assertThat(context).hasSingleBean(VisionModelClient.class);
                    assertThat(context).hasSingleBean(OpenAiCompatibleHttpClient.class);
                    assertThat(context).doesNotHaveBean(OllamaHttpClient.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({OllamaProperties.class, OpenAiProperties.class, ProcessingProperties.class})
    @Import({OllamaHttpClient.class, OpenAiCompatibleHttpClient.class})
    static class TestConfiguration {
    }
}
