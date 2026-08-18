package com.xiwang.phototagautogen.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xiwang.phototagautogen.config.ImmichProperties;
import com.xiwang.phototagautogen.config.ProcessingProperties;
import com.xiwang.phototagautogen.domain.AssetDetail;
import com.xiwang.phototagautogen.domain.AssetPage;
import com.xiwang.phototagautogen.domain.ImmichAlbum;
import com.xiwang.phototagautogen.domain.ImmichAsset;
import com.xiwang.phototagautogen.domain.ImmichTag;
import com.xiwang.phototagautogen.domain.TagIndex;
import com.xiwang.phototagautogen.domain.TagPath;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Component
@Slf4j
public class ImmichHttpClient implements ImmichClient {
    private static final String API_KEY_HEADER = "x-api-key";

    private static final int TAG_ALREADY_EXISTS_STATUS = 400;

    private static final int TAG_ASSETS_NOT_FOUND_STATUS = 404;

    private static final String TAG_ALREADY_EXISTS_MESSAGE = "A tag with that name already exists";

    private static final int TAG_CONFLICT_REFRESH_ATTEMPTS = 2;

    private final String baseUrl;
    private final String apiKey;
    private final ObjectMapper objectMapper;
    private final HttpExecutor httpExecutor;
    private final Duration requestTimeout;

    public ImmichHttpClient(ImmichProperties properties, ProcessingProperties processingProperties,
                            ObjectMapper objectMapper) {
        this.baseUrl = stripTrailingSlash(properties.getBaseUrl());
        this.apiKey = properties.getApiKey();
        this.objectMapper = objectMapper;
        this.httpExecutor = new HttpExecutor(processingProperties.getConnectTimeoutSeconds(),
                processingProperties.getMaxRetries());
        this.requestTimeout = Duration.ofSeconds(processingProperties.getHttpTimeoutSeconds());
    }

    @Override
    public void validateConnection() {
        sendJson(get("/api/server/version"));
    }

    @Override
    public AssetPage listImages(int page, int size) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("page", page);
        body.put("size", size);
        body.put("type", "IMAGE");
        body.put("withDeleted", false);
        body.put("withStacked", false);
        JsonNode root = sendJson(jsonRequest("POST", "/api/search/metadata", body));
        JsonNode items = findItems(root);
        List<ImmichAsset> images = new ArrayList<>();
        for (JsonNode item : items) {
            UUID id = UUID.fromString(item.path("id").asText());
            ImmichAsset asset = new ImmichAsset(id, item.path("type").asText(),
                    item.path("isTrashed").asBoolean(false), item.path("isArchived").asBoolean(false),
                    parseInstant(item.path("fileModifiedAt").asText(null)));
            if (asset.isImage() && !asset.trashed()) {
                images.add(asset);
            }
        }
        JsonNode assets = root.path("assets");
        boolean hasMore = assets.path("nextPage").isTextual() && !assets.path("nextPage").asText().isBlank();
        if (!hasMore && assets.path("total").canConvertToInt() && page * size < assets.path("total").asInt()) {
            hasMore = true;
        }
        return new AssetPage(images, hasMore);
    }

    @Override
    public List<ImmichAlbum> listAlbums() {
        return parseAlbums(sendJson(get("/api/albums")));
    }

    @Override
    public List<ImmichAlbum> listAlbumsByAsset(UUID assetId) {
        return parseAlbums(sendJson(get("/api/albums?assetId=" + assetId)));
    }

    private List<ImmichAlbum> parseAlbums(JsonNode root) {
        List<ImmichAlbum> albums = new ArrayList<>();
        if (!root.isArray()) {
            return albums;
        }
        for (JsonNode item : root) {
            UUID id = parseUuid(textOrNull(item.get("id")));
            if (id == null) {
                continue;
            }
            List<UUID> assetIds = new ArrayList<>();
            JsonNode assets = item.path("assets");
            if (assets.isArray()) {
                for (JsonNode asset : assets) {
                    UUID assetId = parseUuid(textOrNull(asset.get("id")));
                    if (assetId != null) {
                        assetIds.add(assetId);
                    }
                }
            }
            albums.add(new ImmichAlbum(id, textOrNull(item.get("albumName")), assetIds));
        }
        return albums;
    }

    @Override
    public AssetDetail getAsset(UUID assetId) {
        JsonNode root = sendJson(get("/api/assets/" + assetId));
        List<ImmichTag> tags = parseTags(root.path("tags"));
        return new AssetDetail(assetId, root.path("type").asText(), textOrNull(root.get("description")),
                parseInstant(root.path("fileModifiedAt").asText(null)), tags);
    }

    @Override
    public byte[] downloadPreview(UUID assetId) {
        return httpExecutor.execute(get("/api/assets/" + assetId + "/thumbnail?size=preview&format=JPEG")).body();
    }

    @Override
    public void updateDescription(UUID assetId, String description) {
        ObjectNode body = objectMapper.createObjectNode().put("description", description);
        sendJson(jsonRequest("PUT", "/api/assets/" + assetId, body));
    }

    @Override
    public TagIndex listTags() {
        JsonNode root = sendJson(get("/api/tags"));
        JsonNode items = findItems(root);
        TagIndex index = new TagIndex();
        index.addAll(parseTags(items));
        return index;
    }

    @Override
    public UUID ensureTagPath(TagPath path, TagIndex tagIndex) {
        synchronized (tagIndex) {
            return ensureTag(path.segments(), tagIndex);
        }
    }

    private UUID ensureTag(List<String> segments, TagIndex tagIndex) {
        String currentPath = String.join("/", segments);
        ImmichTag existing = tagIndex.find(currentPath);
        if (existing != null) {
            return existing.id();
        }

        UUID parentId = null;
        if (segments.size() > 1) {
            List<String> parentSegments = segments.subList(0, segments.size() - 1);
            String parentPath = String.join("/", parentSegments);
            ImmichTag parent = tagIndex.find(parentPath);
            parentId = parent == null ? ensureTag(parentSegments, tagIndex) : parent.id();
        }

        String name = segments.getLast();
        ObjectNode body = objectMapper.createObjectNode().put("name", name);
        if (parentId != null) {
            body.put("parentId", parentId.toString());
        }
        log.info("请求创建标签：{}，完整标签：{}", name, currentPath);
        JsonNode created;
        try {
            created = sendJson(jsonRequest("POST", "/api/tags", body));
        } catch (RemoteCallException e) {
            if (!isTagAlreadyExistsConflict(e)) {
                throw e;
            }
            return resolveTagAfterConflict(currentPath, tagIndex, e);
        }
        UUID createdId = parseUuid(textOrNull(created.get("id")));
        if (createdId == null) {
            TagIndex refreshed = listTags();
            ImmichTag refreshedTag = refreshed.find(currentPath);
            if (refreshedTag == null) {
                throw new RemoteCallException("Immich 创建标签后无法找到标签路径: " + currentPath, -1);
            }
            tagIndex.addAll(refreshed.all());
            return refreshedTag.id();
        }
        tagIndex.add(new ImmichTag(createdId, name, parentId), currentPath);
        return createdId;
    }

    private UUID resolveTagAfterConflict(String currentPath, TagIndex tagIndex, RemoteCallException conflict) {
        log.warn("创建标签发生名称冲突，刷新标签索引后复用已有标签 path={}, status={}",
                currentPath, conflict.statusCode());
        for (int attempt = 1; attempt <= TAG_CONFLICT_REFRESH_ATTEMPTS; attempt++) {
            TagIndex refreshed = listTags();
            tagIndex.addAll(refreshed.all());
            ImmichTag existing = tagIndex.find(currentPath);
            if (existing != null) {
                log.info("已从刷新后的标签索引复用标签 path={}, tagId={}, refreshAttempt={}",
                        currentPath, existing.id(), attempt);
                return existing.id();
            }
        }
        throw new RemoteCallException("Immich 标签创建发生名称冲突，但刷新后无法找到标签路径: " + currentPath,
                conflict.statusCode(), conflict);
    }

    private boolean isTagAlreadyExistsConflict(RemoteCallException exception) {
        return exception.statusCode() == TAG_ALREADY_EXISTS_STATUS
                && exception.getMessage() != null
                && exception.getMessage().contains(TAG_ALREADY_EXISTS_MESSAGE);
    }

    @Override
    public void attachTags(UUID assetId, Collection<UUID> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return;
        }
        List<UUID> batchTagIds = new ArrayList<>();
        for (UUID tagId : tagIds) {
            ObjectNode body = objectMapper.createObjectNode();
            // Immich v3 起单标签关联接口改用 ids 字段，旧版本使用 assetIds，同时携带以兼容
            body.putArray("assetIds").add(assetId.toString());
            body.putArray("ids").add(assetId.toString());
            try {
                sendJson(jsonRequest("PUT", "/api/tags/" + tagId + "/assets", body));
            } catch (RemoteCallException e) {
                if (e.statusCode() == TAG_ASSETS_NOT_FOUND_STATUS) {
                    log.warn("单标签关联接口不可用，标签改用批量接口关联 tagId={}, status={}",
                            tagId, e.statusCode());
                    batchTagIds.add(tagId);
                } else {
                    throw e;
                }
            }
        }
        if (!batchTagIds.isEmpty()) {
            ObjectNode objectNode = objectMapper.createObjectNode();
            objectNode.putArray("assetIds")
                    .add(assetId.toString());
            ArrayNode tagIdsNode = objectNode.putArray("tagIds");
            batchTagIds.forEach(tagId -> tagIdsNode.add(tagId.toString()));
            sendJson(jsonRequest("PUT", "/api/tags/assets", objectNode));
        }
    }

    private HttpRequest get(String path) {
        return requestBuilder(path).GET().build();
    }

    private HttpRequest jsonRequest(String method, String path, JsonNode body) {
        try {
            return requestBuilder(path)
                    .header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(body)))
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("序列化 Immich 请求失败", e);
        }
    }

    private HttpRequest.Builder requestBuilder(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(requestTimeout)
                .header(API_KEY_HEADER, apiKey)
                .header("Accept", "application/json");
    }

    private JsonNode sendJson(HttpRequest request) {
        try {
            log.debug("{}请求地址:{}", request.method(), request.uri());
            HttpResponse<byte[]> response = httpExecutor.execute(request);
            if (response.body().length == 0) {
                return objectMapper.createObjectNode();
            }
            return objectMapper.readTree(response.body());
        } catch (RemoteCallException e) {
            throw e;
        } catch (Exception e) {
            throw new RemoteCallException("解析 Immich 响应失败", e);
        }
    }

    private JsonNode findItems(JsonNode root) {
        if (root.isArray()) {
            return root;
        }
        if (root.path("items").isArray()) {
            return root.path("items");
        }
        if (root.path("assets").isArray()) {
            return root.path("assets");
        }
        if (root.path("assets").path("items").isArray()) {
            return root.path("assets").path("items");
        }
        throw new RemoteCallException("Immich 返回了无法识别的分页结构", -1);
    }

    private List<ImmichTag> parseTags(JsonNode items) {
        List<ImmichTag> tags = new ArrayList<>();
        if (!items.isArray()) {
            return tags;
        }
        for (JsonNode item : items) {
            UUID parentId = parseUuid(textOrNull(item.get("parentId")));
            tags.add(new ImmichTag(UUID.fromString(item.path("id").asText()), item.path("name").asText(), parentId));
        }
        return tags;
    }

    private static UUID parseUuid(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
