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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImmichHttpClientTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final UUID assetId = UUID.randomUUID();
    private final UUID videoAssetId = UUID.randomUUID();
    private final List<String> requests = new ArrayList<>();
    private final List<JsonNode> createdTagRequests = new ArrayList<>();
    private final List<UUID> createdTagIds = new ArrayList<>();
    private final List<ImmichTag> listedTags = new ArrayList<>();
    private JsonNode attachedTagsRequest;
    private HttpServer server;
    private UUID createdTagId;
    private String conflictingTagName;
    private UUID conflictingTagId;
    private boolean exposeConflictingTag = true;
    private boolean conflictConsumed;
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
        assertThat(requests).contains("PUT /api/tags/assets");
        assertThat(attachedTagsRequest.path("assetIds").get(0).asText()).isEqualTo(assetId.toString());
        assertThat(attachedTagsRequest.path("tagIds").get(0).asText()).isEqualTo(createdTagId.toString());
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
    void 创建根标签发生名称冲突时应刷新索引并继续创建剩余层级() {
        UUID rootId = UUID.randomUUID();
        conflictingTagName = "人物";
        conflictingTagId = rootId;
        TagIndex tags = new TagIndex();

        UUID leafId = client.ensureTagPath(new TagPath(List.of("人物", "人数", "单人")), tags);

        assertThat(leafId).isEqualTo(createdTagIds.getLast());
        assertThat(createdTagRequests)
                .extracting(body -> body.path("name").asText())
                .containsExactly("人物", "人数", "单人");
        assertThat(tags.find("人物").id()).isEqualTo(rootId);
        assertThat(tags.find("人物/人数/单人").id()).isEqualTo(leafId);
        assertThat(requests).contains("GET /api/tags");
    }

    @Test
    void 创建父标签发生名称冲突时应复用父级并创建叶子标签() {
        UUID rootId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        conflictingTagName = "人数";
        conflictingTagId = parentId;
        listedTags.add(new ImmichTag(rootId, "人物", null));
        TagIndex tags = new TagIndex();
        tags.add(new ImmichTag(rootId, "人物", null), "人物");

        UUID leafId = client.ensureTagPath(new TagPath(List.of("人物", "人数", "单人")), tags);

        assertThat(leafId).isEqualTo(createdTagIds.getLast());
        assertThat(createdTagRequests)
                .extracting(body -> body.path("name").asText())
                .containsExactly("人数", "单人");
        assertThat(createdTagRequests.getFirst().path("parentId").asText()).isEqualTo(rootId.toString());
        assertThat(tags.find("人物/人数").id()).isEqualTo(parentId);
    }

    @Test
    void 创建叶子标签发生名称冲突时应直接复用刷新后的叶子标签() {
        UUID rootId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        UUID leafId = UUID.randomUUID();
        conflictingTagName = "单人";
        conflictingTagId = leafId;
        listedTags.addAll(List.of(
                new ImmichTag(rootId, "人物", null),
                new ImmichTag(parentId, "人数", rootId),
                new ImmichTag(leafId, "单人", parentId)));
        TagIndex tags = new TagIndex();
        tags.add(new ImmichTag(rootId, "人物", null), "人物");
        tags.add(new ImmichTag(parentId, "人数", rootId), "人物/人数");

        UUID resolvedId = client.ensureTagPath(new TagPath(List.of("人物", "人数", "单人")), tags);

        assertThat(resolvedId).isEqualTo(leafId);
        assertThat(createdTagRequests).hasSize(1);
        assertThat(tags.find("人物/人数/单人").id()).isEqualTo(leafId);
    }

    @Test
    void 名称冲突但刷新后找不到完整路径时应抛出明确异常() {
        conflictingTagName = "人物";
        conflictingTagId = UUID.randomUUID();
        exposeConflictingTag = false;

        assertThatThrownBy(() -> client.ensureTagPath(
                new TagPath(List.of("人物", "人数", "单人")), new TagIndex()))
                .isInstanceOf(RemoteCallException.class)
                .hasMessageContaining("名称冲突")
                .hasMessageContaining("人物");
        assertThat(requests.stream().filter(request -> request.equals("GET /api/tags")).count())
                .isEqualTo(2);
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

    private String serializeTags() throws IOException {
        var array = objectMapper.createArrayNode();
        for (ImmichTag tag : listedTags) {
            var node = objectMapper.createObjectNode()
                    .put("id", tag.id().toString())
                    .put("name", tag.name());
            if (tag.parentId() != null) {
                node.put("parentId", tag.parentId().toString());
            }
            array.add(node);
        }
        return objectMapper.writeValueAsString(array);
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
            response = serializeTags();
        } else if ("POST".equals(method) && "/api/tags".equals(path)) {
            JsonNode requestBody = objectMapper.readTree(exchange.getRequestBody().readAllBytes());
            createdTagRequests.add(requestBody);
            String name = requestBody.path("name").asText();
            UUID parentId = requestBody.hasNonNull("parentId")
                    ? UUID.fromString(requestBody.path("parentId").asText()) : null;
            if (name.equals(conflictingTagName) && !conflictConsumed) {
                conflictConsumed = true;
                if (exposeConflictingTag) {
                    listedTags.add(new ImmichTag(conflictingTagId, name, parentId));
                }
                response = "{\"message\":\"A tag with that name already exists\"}";
                status = 400;
            } else {
                createdTagId = UUID.randomUUID();
                createdTagIds.add(createdTagId);
                listedTags.add(new ImmichTag(createdTagId, name, parentId));
                response = "{\"id\":\"" + createdTagId + "\"}";
                status = 201;
            }
        } else if ("PUT".equals(method) && "/api/tags/assets".equals(path)) {
            attachedTagsRequest = objectMapper.readTree(exchange.getRequestBody().readAllBytes());
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
