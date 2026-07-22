package com.xiwang.phototagautogen.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xiwang.phototagautogen.config.OllamaProperties;
import com.xiwang.phototagautogen.config.ProcessingProperties;
import com.xiwang.phototagautogen.domain.ImageAnalysis;
import com.xiwang.phototagautogen.domain.Taxonomy;
import com.xiwang.phototagautogen.service.TaxonomyLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OllamaHttpClientTest {
    private static final int CONTEXT_WINDOW = 8_192;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AtomicReference<JsonNode> requestBody = new AtomicReference<>();
    private final AtomicReference<JsonNode> unloadRequestBody = new AtomicReference<>();
    private HttpServer server;
    private OllamaHttpClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", this::handleChat);
        server.createContext("/api/generate", this::handleGenerate);
        server.start();

        OllamaProperties ollama = new OllamaProperties();
        ollama.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        ollama.setContextWindow(CONTEXT_WINDOW);
        ollama.setKeepAlive("2m");
        ProcessingProperties processing = new ProcessingProperties();
        processing.setConnectTimeoutSeconds(2);
        processing.setModelTimeoutSeconds(5);
        client = new OllamaHttpClient(ollama, processing, objectMapper);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void 分析图片时应设置人像协议和上下文窗口() {
        Taxonomy taxonomy = new TaxonomyLoader().load();
        ImageAnalysis analysis = client.analyze(new byte[] {1, 2, 3}, taxonomy);

        assertThat(analysis.description()).isEqualTo("测试图片描述");
        assertThat(analysis.portraitSubject()).isFalse();
        assertThat(analysis.tags()).hasSize(1);
        assertThat(analysis.tags().getFirst().path()).containsExactly("风光", "季节", "春");
        assertThat(analysis.tags().getFirst().parentTag()).isEqualTo("风光/季节");
        assertThat(requestBody.get().path("options").path("num_ctx").asInt()).isEqualTo(CONTEXT_WINDOW);
        assertThat(requestBody.get().path("keep_alive").asText()).isEqualTo("2m");
        assertThat(requestBody.get().path("format").path("properties").path("portraitSubject").path("type").asText())
                .isEqualTo("boolean");
        assertThat(requestBody.get().path("format").path("required"))
                .extracting(JsonNode::asText)
                .contains("portraitSubject");
        JsonNode tagSchema = requestBody.get().path("format").path("properties").path("tags").path("items");
        assertThat(tagSchema.path("properties").path("path").path("enum"))
                .extracting(JsonNode::asText)
                .contains("人像/配饰/无配饰", "风光/季节/春");
        assertThat(tagSchema.path("properties").path("parentTag").path("type").asText()).isEqualTo("string");
        assertThat(tagSchema.path("required")).extracting(JsonNode::asText).contains("parentTag");
        assertThat(requestBody.get().path("messages").get(1).path("content").asText())
                .contains("portraitSubject=true", "人脸角度、姿态、景别、服饰类型、主体颜色、配饰、场景",
                        "多人照片只以最主要或最清晰的人物为准", "只描述主体人物", "人像/配饰/无配饰");
    }

    @Test
    void 释放资源时应请求Ollama立即卸载模型() {
        client.releaseResources();

        assertThat(unloadRequestBody.get().path("model").asText()).isEqualTo("qwen2.5vl:7b");
        assertThat(unloadRequestBody.get().path("keep_alive").asInt()).isZero();
    }

    private void handleChat(HttpExchange exchange) throws IOException {
        requestBody.set(objectMapper.readTree(exchange.getRequestBody().readAllBytes()));
        byte[] response = ("{\"message\":{\"content\":\"{\\\"description\\\":\\\"测试图片描述\\\","
                + "\\\"portraitSubject\\\":false,\\\"tags\\\":[{\\\"path\\\":\\\"风光/季节/春\\\",\\\"parentTag\\\":\\\"风光/季节\\\",\\\"confidence\\\":0.9}]}\"}}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        try (var output = exchange.getResponseBody()) {
            output.write(response);
        }
    }

    private void handleGenerate(HttpExchange exchange) throws IOException {
        unloadRequestBody.set(objectMapper.readTree(exchange.getRequestBody().readAllBytes()));
        byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        try (var output = exchange.getResponseBody()) {
            output.write(response);
        }
    }
}
