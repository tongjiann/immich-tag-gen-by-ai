package com.xiwang.phototagautogen.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xiwang.phototagautogen.config.OllamaProperties;
import com.xiwang.phototagautogen.config.ProcessingProperties;
import com.xiwang.phototagautogen.domain.ImageAnalysis;
import com.xiwang.phototagautogen.domain.Taxonomy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "vision", name = "provider", havingValue = "ollama", matchIfMissing = true)
public class OllamaHttpClient implements VisionModelClient {
    private final String baseUrl;
    private final String model;
    private final int contextWindow;
    private final String keepAlive;
    private final ObjectMapper objectMapper;
    private final VisionModelSupport visionModelSupport;
    private final HttpExecutor httpExecutor;
    private final Duration requestTimeout;

    public OllamaHttpClient(OllamaProperties properties, ProcessingProperties processingProperties,
                            ObjectMapper objectMapper) {
        this.baseUrl = stripTrailingSlash(properties.getBaseUrl());
        this.model = properties.getModel();
        this.contextWindow = properties.getContextWindow();
        this.keepAlive = properties.getKeepAlive();
        this.objectMapper = objectMapper;
        this.visionModelSupport = new VisionModelSupport(objectMapper);
        this.httpExecutor = new HttpExecutor(processingProperties.getConnectTimeoutSeconds(),
                processingProperties.getMaxRetries());
        this.requestTimeout = Duration.ofSeconds(processingProperties.getModelTimeoutSeconds());
    }

    @Override
    public void validateConnection() {
        ObjectNode body = objectMapper.createObjectNode().put("name", model);
        sendJson(request("POST", "/api/show", body));
    }

    @Override
    public ImageAnalysis analyze(byte[] image, Taxonomy taxonomy) {
        String encodedImage = Base64.getEncoder().encodeToString(image);
        String prompt = visionModelSupport.buildPrompt(taxonomy);
        try {
            return parseAnalysis(sendChat(encodedImage, prompt, taxonomy));
        } catch (IllegalArgumentException firstFailure) {
            log.warn("Ollama 首次返回格式不合法，正在执行一次格式修复请求 model={}", model);
            return parseAnalysis(sendChat(encodedImage, visionModelSupport.repairPrompt(prompt), taxonomy));
        }
    }

    @Override
    public String modelName() {
        return model;
    }

    @Override
    public boolean supportsResourceRelease() {
        return true;
    }

    @Override
    public void releaseResources() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("keep_alive", 0);
        sendJson(request("POST", "/api/generate", body));
    }

    private JsonNode sendChat(String encodedImage, String prompt, Taxonomy taxonomy) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("stream", false);
        body.put("keep_alive", keepAlive);
        body.set("format", visionModelSupport.schema(taxonomy));
        body.putObject("options")
                .put("temperature", 0.1)
                .put("num_ctx", contextWindow);

        ArrayNode messages = body.putArray("messages");
        ObjectNode system = messages.addObject();
        system.put("role", "system");
        system.put("content", VisionModelSupport.SYSTEM_PROMPT);
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", prompt);
        user.putArray("images").add(encodedImage);

        return sendJson(request("POST", "/api/chat", body));
    }

    private ImageAnalysis parseAnalysis(JsonNode response) {
        return visionModelSupport.parseAnalysis(response.path("message").path("content").asText(null), "Ollama");
    }

    private HttpRequest request(String method, String path, JsonNode body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(requestTimeout)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json");
            return builder.method(method,
                    HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(body))).build();
        } catch (Exception e) {
            throw new IllegalStateException("序列化 Ollama 请求失败", e);
        }
    }

    private JsonNode sendJson(HttpRequest request) {
        try {
            HttpResponse<byte[]> response = httpExecutor.execute(request);
            return objectMapper.readTree(response.body());
        } catch (RemoteCallException e) {
            throw e;
        } catch (Exception e) {
            throw new RemoteCallException("解析 Ollama 响应失败", e);
        }
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
