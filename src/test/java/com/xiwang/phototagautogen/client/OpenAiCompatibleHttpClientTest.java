package com.xiwang.phototagautogen.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xiwang.phototagautogen.config.OpenAiProperties;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleHttpClientTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AtomicReference<JsonNode> requestBody = new AtomicReference<>();
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private HttpServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", this::handleModels);
        server.createContext("/v1/chat/completions", this::handleChat);
        server.createContext("/html/models", this::handleHtmlModels);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void 分析图片时应发送人像协议并解析结果() {
        OpenAiCompatibleHttpClient client = client("test-key");
        Taxonomy taxonomy = new TaxonomyLoader().load();

        ImageAnalysis analysis = client.analyze(new byte[] {1, 2, 3}, taxonomy);

        assertThat(analysis.description()).isEqualTo("测试图片描述");
        assertThat(analysis.portraitSubject()).isFalse();
        assertThat(analysis.tags()).hasSize(1);
        assertThat(analysis.tags().getFirst().path()).containsExactly("季节", "春");
        assertThat(requestPath.get()).isEqualTo("/v1/chat/completions");
        assertThat(authorization.get()).isEqualTo("Bearer test-key");
        assertThat(requestBody.get().path("model").asText()).isEqualTo("vision-model");
        assertThat(requestBody.get().path("stream").asBoolean()).isFalse();
        assertThat(requestBody.get().path("messages").get(1).path("content").get(1)
                .path("image_url").path("url").asText()).isEqualTo("data:image/jpeg;base64,AQID");
        assertThat(requestBody.get().path("messages").get(1).path("content").get(0).path("text").asText())
                .contains("portraitSubject=true", "多人照片只以最主要或最清晰的人物为准",
                        "人像/配饰/无配饰", "portraitSubject=false 时禁止返回任何‘人像’标签");
        JsonNode schema = requestBody.get().path("response_format").path("json_schema").path("schema");
        assertThat(requestBody.get().path("response_format").path("type").asText()).isEqualTo("json_schema");
        assertThat(requestBody.get().path("response_format").path("json_schema").path("strict").asBoolean())
                .isTrue();
        assertThat(schema.path("properties").path("portraitSubject").path("type").asText()).isEqualTo("boolean");
        assertThat(schema.path("required")).extracting(JsonNode::asText).contains("portraitSubject");
        assertThat(schema.path("properties").path("tags").path("items").path("properties").path("path").path("enum"))
                .extracting(JsonNode::asText)
                .contains("人像/配饰/无配饰", "季节/春");
    }

    @Test
    void APIKey为空时不应发送Authorization请求头() {
        OpenAiCompatibleHttpClient client = client("");

        client.validateConnection();

        assertThat(requestPath.get()).isEqualTo("/v1/models");
        assertThat(authorization.get()).isNull();
    }

    @Test
    void API根路径返回HTML时应提示检查版本前缀() {
        OpenAiCompatibleHttpClient client = client("test-key", "/html/");

        assertThatThrownBy(client::validateConnection)
                .isInstanceOf(RemoteCallException.class)
                .hasMessageContaining("返回 HTML 而不是 JSON")
                .hasMessageContaining("OPENAI_BASE_URL")
                .hasMessageContaining("/v1");
    }

    private OpenAiCompatibleHttpClient client(String apiKey) {
        return client(apiKey, "/v1/");
    }

    private OpenAiCompatibleHttpClient client(String apiKey, String apiPath) {
        OpenAiProperties openAi = new OpenAiProperties();
        openAi.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + apiPath);
        openAi.setApiKey(apiKey);
        openAi.setModel("vision-model");
        ProcessingProperties processing = new ProcessingProperties();
        processing.setConnectTimeoutSeconds(2);
        processing.setModelTimeoutSeconds(5);
        return new OpenAiCompatibleHttpClient(openAi, processing, objectMapper);
    }

    private void handleModels(HttpExchange exchange) throws IOException {
        captureRequest(exchange, null);
        respond(exchange, "application/json", "{\"object\":\"list\",\"data\":[]}");
    }

    private void handleHtmlModels(HttpExchange exchange) throws IOException {
        captureRequest(exchange, null);
        respond(exchange, "text/html; charset=utf-8", "<!doctype html><html><body>管理后台</body></html>");
    }

    private void handleChat(HttpExchange exchange) throws IOException {
        captureRequest(exchange, objectMapper.readTree(exchange.getRequestBody().readAllBytes()));
        ObjectNode analysis = objectMapper.createObjectNode();
        analysis.put("description", "测试图片描述");
        analysis.put("portraitSubject", false);
        analysis.putArray("tags").addObject()
                .put("path", "季节/春")
                .put("confidence", 0.9);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("choices").addObject().putObject("message")
                .put("content", objectMapper.writeValueAsString(analysis));
        respond(exchange, "application/json", objectMapper.writeValueAsString(response));
    }

    private void captureRequest(HttpExchange exchange, JsonNode body) {
        requestPath.set(exchange.getRequestURI().getPath());
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        requestBody.set(body);
    }

    private void respond(HttpExchange exchange, String contentType, String responseBody) throws IOException {
        byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(200, response.length);
        try (var output = exchange.getResponseBody()) {
            output.write(response);
        }
    }
}
