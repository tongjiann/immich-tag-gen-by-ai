package com.xiwang.phototagautogen.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xiwang.phototagautogen.config.ImmichProperties;
import com.xiwang.phototagautogen.config.ProcessingProperties;
import com.xiwang.phototagautogen.domain.AssetDetail;
import com.xiwang.phototagautogen.domain.AssetPage;
import com.xiwang.phototagautogen.domain.ImmichTag;
import com.xiwang.phototagautogen.domain.TagIndex;
import com.xiwang.phototagautogen.domain.TagPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ImmichHttpClientTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final UUID assetId = UUID.randomUUID();
    private final UUID videoAssetId = UUID.randomUUID();
    private final List<String> requests = new ArrayList<>();
    private final List<JsonNode> createdTagRequests = new ArrayList<>();
    private final List<UUID> createdTagIds = new ArrayList<>();
    private HttpServer server;
    private UUID createdTagId;
    private ImmichHttpClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", this::handle);
        server.start();

        ImmichProperties immich = new ImmichProperties();
        immich.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        immich.setApiKey("test-key");
        ProcessingProperties processing = new ProcessingProperties();
        processing.setConnectTimeoutSeconds(2);
        processing.setHttpTimeoutSeconds(5);
        client = new ImmichHttpClient(immich, processing, objectMapper);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void 使用当前Immich元数据搜索和标签关联接口() {
        client.validateConnection();
        AssetPage page = client.listImages(1, 100);
        AssetDetail detail = client.getAsset(assetId);
        TagIndex tags = client.listTags();
        UUID leafId = client.ensureTagPath(new TagPath(List.of("人物", "人数", "单人")), tags);
        client.attachTags(assetId, List.of(leafId));

        assertThat(page.assets()).hasSize(1);
        assertThat(page.assets().getFirst().id()).isEqualTo(assetId);
        assertThat(page.assets().getFirst().isImage()).isTrue();
        assertThat(detail.isImage()).isTrue();
        assertThat(leafId).isEqualTo(createdTagId);
        assertThat(createdTagRequests)
                .extracting(body -> body.path("name").asText())
                .containsExactly("人物", "人数", "单人");
        assertThat(createdTagRequests.getFirst().has("parentId")).isFalse();
        assertThat(createdTagRequests.get(1).path("parentId").asText())
                .isEqualTo(createdTagIds.getFirst().toString());
        assertThat(createdTagRequests.get(2).path("parentId").asText())
                .isEqualTo(createdTagIds.get(1).toString());
        assertThat(requests).contains("GET /api/server/version");
        assertThat(requests).contains("POST /api/search/metadata");
        assertThat(requests).contains("GET /api/assets/" + assetId);
        assertThat(requests).contains("GET /api/tags");
        assertThat(requests).contains("POST /api/tags");
        assertThat(requests).contains("PUT /api/tags/" + createdTagId + "/assets");
    }

    @Test
    void 完整标签路径已存在时应直接复用叶子标签Id() {
        UUID rootId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        UUID leafId = UUID.randomUUID();
        TagIndex tags = new TagIndex();
        tags.add(new ImmichTag(rootId, "人物", null), "人物");
        tags.add(new ImmichTag(parentId, "人数", rootId), "人物/人数");
        tags.add(new ImmichTag(leafId, "单人", parentId), "人物/人数/单人");

        UUID resolvedId = client.ensureTagPath(new TagPath(List.of("人物", "人数", "单人")), tags);

        assertThat(resolvedId).isEqualTo(leafId);
        assertThat(createdTagRequests).isEmpty();
    }

    @Test
    void 二级父路径已存在时应仅创建叶子标签() {
        UUID rootId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        TagIndex tags = new TagIndex();
        tags.add(new ImmichTag(rootId, "人物", null), "人物");
        tags.add(new ImmichTag(parentId, "人数", rootId), "人物/人数");

        UUID leafId = client.ensureTagPath(new TagPath(List.of("人物", "人数", "单人")), tags);

        assertThat(leafId).isEqualTo(createdTagId);
        assertThat(createdTagRequests).hasSize(1);
        assertThat(createdTagRequests.getFirst().path("name").asText()).isEqualTo("单人");
        assertThat(createdTagRequests.getFirst().path("parentId").asText()).isEqualTo(parentId.toString());
        assertThat(tags.find("人物/人数/单人").id()).isEqualTo(leafId);
    }

    @Test
    void 仅一级标签存在时应依次创建缺失的父级和叶子标签() {
        UUID rootId = UUID.randomUUID();
        TagIndex tags = new TagIndex();
        tags.add(new ImmichTag(rootId, "人物", null), "人物");

        UUID leafId = client.ensureTagPath(new TagPath(List.of("人物", "人数", "单人")), tags);

        assertThat(createdTagRequests)
                .extracting(body -> body.path("name").asText())
                .containsExactly("人数", "单人");
        assertThat(createdTagRequests.getFirst().path("parentId").asText()).isEqualTo(rootId.toString());
        assertThat(createdTagRequests.get(1).path("parentId").asText())
                .isEqualTo(createdTagIds.getFirst().toString());
        assertThat(leafId).isEqualTo(createdTagIds.get(1));
    }

    @Test
    void 多个计划标签共享父路径时应只创建一次父级() {
        UUID rootId = UUID.randomUUID();
        TagIndex tags = new TagIndex();
        tags.add(new ImmichTag(rootId, "人物", null), "人物");

        UUID singleId = client.ensureTagPath(new TagPath(List.of("人物", "人数", "单人")), tags);
        UUID groupId = client.ensureTagPath(new TagPath(List.of("人物", "人数", "多人")), tags);

        assertThat(createdTagRequests)
                .extracting(body -> body.path("name").asText())
                .containsExactly("人数", "单人", "多人");
        assertThat(createdTagRequests.get(1).path("parentId").asText())
                .isEqualTo(createdTagIds.getFirst().toString());
        assertThat(createdTagRequests.get(2).path("parentId").asText())
                .isEqualTo(createdTagIds.getFirst().toString());
        assertThat(singleId).isEqualTo(createdTagIds.get(1));
        assertThat(groupId).isEqualTo(createdTagIds.get(2));
    }

    private void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        requests.add(method + " " + path);
        String response;
        int status = 200;
        if ("GET".equals(method) && "/api/server/version".equals(path)) {
            response = "{\"major\":\"2\",\"minor\":\"0\",\"patch\":\"0\"}";
        } else if ("POST".equals(method) && "/api/search/metadata".equals(path)) {
            response = "{\"albums\":[],\"assets\":{\"count\":2,\"facets\":[],\"items\":["
                    + "{\"id\":\"" + assetId + "\",\"type\":\"IMAGE\",\"isTrashed\":false,"
                    + "\"isArchived\":false,\"fileModifiedAt\":\"2026-07-21T00:00:00Z\"},"
                    + "{\"id\":\"" + videoAssetId + "\",\"type\":\"VIDEO\",\"isTrashed\":false,"
                    + "\"isArchived\":false,\"fileModifiedAt\":\"2026-07-21T00:00:00Z\"}],"
                    + "\"nextPage\":null,\"total\":2}}";
        } else if ("GET".equals(method) && ("/api/assets/" + assetId).equals(path)) {
            response = "{\"id\":\"" + assetId + "\",\"type\":\"IMAGE\",\"description\":null,"
                    + "\"fileModifiedAt\":\"2026-07-21T00:00:00Z\",\"tags\":[]}";
        } else if ("GET".equals(method) && "/api/tags".equals(path)) {
            response = "[]";
        } else if ("POST".equals(method) && "/api/tags".equals(path)) {
            createdTagRequests.add(objectMapper.readTree(exchange.getRequestBody().readAllBytes()));
            createdTagId = UUID.randomUUID();
            createdTagIds.add(createdTagId);
            response = "{\"id\":\"" + createdTagId + "\"}";
            status = 201;
        } else if ("PUT".equals(method) && path.startsWith("/api/tags/") && path.endsWith("/assets")) {
            response = "[]";
        } else {
            status = 404;
            response = "{\"message\":\"not found\"}";
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
