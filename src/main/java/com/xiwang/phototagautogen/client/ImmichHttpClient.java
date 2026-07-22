package com.xiwang.phototagautogen.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xiwang.phototagautogen.config.ImmichProperties;
import com.xiwang.phototagautogen.config.ProcessingProperties;
import com.xiwang.phototagautogen.domain.AssetDetail;
import com.xiwang.phototagautogen.domain.AssetPage;
import com.xiwang.phototagautogen.domain.ImmichAsset;
import com.xiwang.phototagautogen.domain.ImmichTag;
import com.xiwang.phototagautogen.domain.TagIndex;
import com.xiwang.phototagautogen.domain.TagPath;
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
public class ImmichHttpClient implements ImmichClient {
    private static final String API_KEY_HEADER = "x-api-key";

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
        UUID parentId = null;
        String currentPath = "";
        for (String segment : path.segments()) {
            currentPath = currentPath.isEmpty() ? segment : currentPath + "/" + segment;
            ImmichTag existing = tagIndex.find(currentPath);
            if (existing != null) {
                parentId = existing.id();
                continue;
            }
            ObjectNode body = objectMapper.createObjectNode().put("name", segment);
            if (parentId != null) {
                body.put("parentId", parentId.toString());
            }
            JsonNode created = sendJson(jsonRequest("POST", "/api/tags", body));
            UUID createdId = parseUuid(textOrNull(created.get("id")));
            if (createdId == null) {
                TagIndex refreshed = listTags();
                ImmichTag refreshedTag = refreshed.find(currentPath);
                if (refreshedTag == null) {
                    throw new RemoteCallException("Immich 创建标签后无法找到标签路径: " + currentPath, -1);
                }
                tagIndex.addAll(refreshed.all());
                createdId = refreshedTag.id();
            } else {
                tagIndex.add(new ImmichTag(createdId, segment, parentId), currentPath);
            }
            parentId = createdId;
        }
        return parentId;
    }

    @Override
    public void attachTags(UUID assetId, Collection<UUID> tagIds) {
        for (UUID tagId : tagIds) {
            ObjectNode body = objectMapper.createObjectNode();
            body.putArray("ids").add(assetId.toString());
            try {
                sendJson(jsonRequest("PUT", "/api/tags/" + tagId + "/assets", body));
            } catch (RemoteCallException e) {
                if (e.statusCode() != 404) {
                    throw e;
                }
                ObjectNode fallbackBody = objectMapper.createObjectNode();
                fallbackBody.putArray("assetIds").add(assetId.toString());
                fallbackBody.putArray("tagIds").add(tagId.toString());
                sendJson(jsonRequest("PUT", "/api/tags/assets", fallbackBody));
            }
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
