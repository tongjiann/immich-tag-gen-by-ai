package com.xiwang.phototagautogen.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xiwang.phototagautogen.config.OpenAiProperties;
import com.xiwang.phototagautogen.config.ProcessingProperties;
import com.xiwang.phototagautogen.domain.ImageAnalysis;
import com.xiwang.phototagautogen.domain.Taxonomy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "vision", name = "provider", havingValue = "openai")
public class OpenAiCompatibleHttpClient implements VisionModelClient {
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final ObjectMapper objectMapper;
    private final VisionModelSupport visionModelSupport;
    private final HttpExecutor httpExecutor;
    private final Duration requestTimeout;

    public OpenAiCompatibleHttpClient(OpenAiProperties properties, ProcessingProperties processingProperties,
                                      ObjectMapper objectMapper) {
        this.baseUrl = stripTrailingSlash(requireConfigured(properties.getBaseUrl(), "OPENAI_BASE_URL"));
        this.apiKey = properties.getApiKey();
        this.model = requireConfigured(properties.getModel(), "OPENAI_MODEL");
        this.objectMapper = objectMapper;
        this.visionModelSupport = new VisionModelSupport(objectMapper);
        this.httpExecutor = new HttpExecutor(processingProperties.getConnectTimeoutSeconds(),
                processingProperties.getMaxRetries(), HttpClient.Version.HTTP_1_1);
        this.requestTimeout = Duration.ofSeconds(processingProperties.getModelTimeoutSeconds());
    }

    @Override
    public void validateConnection() {
        HttpRequest request = request("GET", "/models", null);
        sendJson(request);
        log.debug("OpenAI 兼容接口连接验证成功 path={}", request.uri().getPath());
    }

    @Override
    public ImageAnalysis analyze(byte[] image, Taxonomy taxonomy) {
        String encodedImage = Base64.getEncoder().encodeToString(image);
        String prompt = visionModelSupport.buildPrompt(taxonomy);
        try {
            return parseAnalysis(sendChat(encodedImage, prompt, taxonomy));
        } catch (IllegalArgumentException firstFailure) {
            log.warn("OpenAI 兼容接口首次返回格式不合法，正在执行一次格式修复请求 model={}", model);
            return parseAnalysis(sendChat(encodedImage, visionModelSupport.repairPrompt(prompt), taxonomy));
        }
    }

    @Override
    public String modelName() {
        return model;
    }

    private JsonNode sendChat(String encodedImage, String prompt, Taxonomy taxonomy) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", 0.1);
        body.put("stream", false);

        ArrayNode messages = body.putArray("messages");
        messages.addObject()
                .put("role", "system")
                .put("content", VisionModelSupport.SYSTEM_PROMPT);
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        ArrayNode content = user.putArray("content");
        content.addObject()
                .put("type", "text")
                .put("text", prompt);
        content.addObject()
                .put("type", "image_url")
                .putObject("image_url")
                .put("url", "data:image/jpeg;base64," + encodedImage);

        ObjectNode jsonSchema = body.putObject("response_format")
                .put("type", "json_schema")
                .putObject("json_schema");
        jsonSchema.put("name", "image_analysis");
        jsonSchema.put("strict", true);
        jsonSchema.set("schema", visionModelSupport.schema(taxonomy));

        return sendJson(request("POST", "/chat/completions", body));
    }

    private ImageAnalysis parseAnalysis(JsonNode response) {
        String content = response.path("choices").path(0).path("message").path("content").asText(null);
        return visionModelSupport.parseAnalysis(content, "OpenAI 兼容接口");
    }

    private HttpRequest request(String method, String path, JsonNode body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(requestTimeout)
                    .header("Accept", "application/json")
                    .header("Accept-Encoding", "identity");
            if (!apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            if (body == null) {
                return builder.method(method, HttpRequest.BodyPublishers.noBody()).build();
            }
            return builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(body)))
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("序列化 OpenAI 兼容接口请求失败", e);
        }
    }

    private JsonNode sendJson(HttpRequest request) {
        HttpResponse<byte[]> response = execute(request);
        try {
            validateJsonResponse(request, response);
            JsonNode body = objectMapper.readTree(response.body());
            if (body == null) {
                throw new IllegalArgumentException("响应体为空");
            }
            return body;
        } catch (Exception e) {
            if (e instanceof RemoteCallException remoteCallException) {
                throw remoteCallException;
            }
            throw new RemoteCallException(
                    "解析 OpenAI 兼容接口响应失败 method=" + request.method()
                            + ", path=" + request.uri().getPath()
                            + ", response=" + preview(response.body()),
                    e);
        }
    }

    private void validateJsonResponse(HttpRequest request, HttpResponse<byte[]> response) {
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (!contentType.toLowerCase(Locale.ROOT).contains("text/html")) {
            return;
        }
        throw new RemoteCallException(
                "OpenAI 兼容接口返回 HTML 而不是 JSON method=" + request.method()
                        + ", target=" + request.uri().getScheme() + "://" + request.uri().getRawAuthority()
                        + request.uri().getRawPath()
                        + ", contentType=" + contentType
                        + "。请检查 OPENAI_BASE_URL 是否指向 API 根路径，当前服务通常需要包含 /v1",
                response.statusCode());
    }

    private HttpResponse<byte[]> execute(HttpRequest request) {
        try {
            return httpExecutor.execute(request);
        } catch (RemoteCallException e) {
            throw e;
        } catch (Exception e) {
            throw new RemoteCallException("调用 OpenAI 兼容接口失败", e);
        }
    }

    private String preview(byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8)
                .replaceAll("\\p{Cntrl}", " ")
                .trim();
        return text.length() <= 500 ? text : text.substring(0, 500);
    }

    private static String requireConfigured(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(propertyName + " 未配置");
        }
        return value.trim();
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
