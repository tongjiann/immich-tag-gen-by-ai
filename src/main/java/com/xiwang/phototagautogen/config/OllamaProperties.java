package com.xiwang.phototagautogen.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "ollama")
@Validated
public class OllamaProperties {
    private static final int DEFAULT_CONTEXT_WINDOW = 8_192;

    @NotBlank
    private String baseUrl = "http://127.0.0.1:11434";

    @NotBlank
    private String model = "qwen2.5vl:7b";

    @Min(1_024)
    private int contextWindow = DEFAULT_CONTEXT_WINDOW;

    @NotBlank
    private String keepAlive = "5m";

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getContextWindow() {
        return contextWindow;
    }

    public void setContextWindow(int contextWindow) {
        this.contextWindow = contextWindow;
    }

    public String getKeepAlive() {
        return keepAlive;
    }

    public void setKeepAlive(String keepAlive) {
        this.keepAlive = keepAlive == null ? null : keepAlive.trim();
    }
}
