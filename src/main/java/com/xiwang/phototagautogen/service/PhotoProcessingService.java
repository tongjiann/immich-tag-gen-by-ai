package com.xiwang.phototagautogen.service;

import com.xiwang.phototagautogen.client.ImmichClient;
import com.xiwang.phototagautogen.client.RemoteCallException;
import com.xiwang.phototagautogen.client.VisionModelClient;
import com.xiwang.phototagautogen.config.ImmichProperties;
import com.xiwang.phototagautogen.config.ProcessingProperties;
import com.xiwang.phototagautogen.domain.*;
import com.xiwang.phototagautogen.state.JsonlStateStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PhotoProcessingService {
    private static final int MAX_TAGS = 15;
    private static final int MAX_MODEL_VALIDATION_RETRIES = 3;
    private static final long MODEL_PROGRESS_INTERVAL_SECONDS = 30;

    private final ImmichClient immichClient;
    private final VisionModelClient visionModelClient;
    private final TaxonomyLoader taxonomyLoader;
    private final JsonlStateStore stateStore;
    private final ProcessingProperties properties;
    private final String immichBaseUrl;
    private Set<UUID> skipAlbumIds = Set.of();
    private Set<String> skipAlbumNames = Set.of();

    public PhotoProcessingService(ImmichClient immichClient, VisionModelClient visionModelClient,
                                  TaxonomyLoader taxonomyLoader, JsonlStateStore stateStore,
                                  ProcessingProperties properties, ImmichProperties immichProperties) {
        this.immichClient = immichClient;
        this.visionModelClient = visionModelClient;
        this.taxonomyLoader = taxonomyLoader;
        this.stateStore = stateStore;
        this.properties = properties;
        this.immichBaseUrl = stripTrailingSlash(immichProperties.getBaseUrl());
    }

    public ProcessingSummary run(boolean force, boolean dryRun, UUID assetId) {
        log.info("正在加载受控标签词表");
        Taxonomy taxonomy = taxonomyLoader.load();
        log.info("受控标签词表加载完成 taxonomyVersion={}, allowedPaths={}",
                taxonomy.version(), taxonomy.allowedPathStrings().size());
        log.info("正在加载 Immich 标签索引");
        TagIndex tagIndex = immichClient.listTags();
        log.info("Immich 标签索引加载完成");
        log.info("现有索引，{}", String.join("，", tagIndex.getFullPathTags()));

        resolveSkippedAlbums();

        ProcessingSummary summary = new ProcessingSummary();
        if (assetId != null) {
            log.info("进入单图处理模式 assetId={}, dryRun={}", assetId, dryRun);
            summary.scanned();
            processAssetSafely(new ImmichAsset(assetId, "IMAGE", false, false, null), taxonomy,
                    tagIndex, force, dryRun, summary);
            return summary;
        }

        log.info("开始全量扫描 pageSize={}, concurrency={}, force={}, dryRun={}",
                properties.getPageSize(), properties.getConcurrency(), force, dryRun);
        int processedSinceModelRelease = 0;
        try (ExecutorService executor = Executors.newFixedThreadPool(properties.getConcurrency(),
                Thread.ofVirtual().name("image-analysis-", 0).factory())) {
            int page = 1;
            while (true) {
                long pageStartedAt = System.nanoTime();
                log.info("正在读取 Immich 图片列表 page={}, pageSize={}", page, properties.getPageSize());
                AssetPage assetPage = immichClient.listImages(page, properties.getPageSize());
                log.info("Immich 图片列表读取完成 page={}, assetCount={}, hasMore={}, elapsedMs={}",
                        page, assetPage.assets().size(), assetPage.hasMore(), elapsedMillis(pageStartedAt));

                List<ImmichAsset> pendingAssets = new ArrayList<>();
                int skippedOnPage = 0;
                for (ImmichAsset asset : assetPage.assets()) {
                    summary.scanned();
                    if (!asset.isImage()) {
                        summary.skipped();
                        skippedOnPage++;
                        log.info("非图片资产，跳过处理 assetId={}, type={}", asset.id(), asset.type());
                        continue;
                    }
                    pendingAssets.add(asset);
                }
                log.info("本页图片任务已准备 page={}, pending={}, skipped={}",
                        page, pendingAssets.size(), skippedOnPage);
                processedSinceModelRelease = processInBatches(executor, pendingAssets, taxonomy, tagIndex,
                        force, dryRun, summary, processedSinceModelRelease);
                log.info("本页图片处理完成 page={}, progress=[{}]", page, summary);
                if (!assetPage.hasMore()) {
                    break;
                }
                page++;
            }
        }
        return summary;
    }

    private void resolveSkippedAlbums() {
        String configured = properties.getSkipAlbums();
        if (configured == null || configured.isBlank()) {
            skipAlbumIds = Set.of();
            skipAlbumNames = Set.of();
            return;
        }
        Set<UUID> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (String identifier : configured.split(",")) {
            String value = identifier.trim();
            if (value.isBlank()) {
                continue;
            }
            UUID albumId = parseUuid(value);
            if (albumId != null) {
                ids.add(albumId);
            } else {
                names.add(value.toLowerCase(Locale.ROOT));
            }
        }
        skipAlbumIds = Set.copyOf(ids);
        skipAlbumNames = Set.copyOf(names);
        log.info("已配置跳过相簿 albumIds={}, albumNames={}", skipAlbumIds, skipAlbumNames);
    }

    private boolean isInSkippedAlbum(UUID assetId) {
        if (skipAlbumIds.isEmpty() && skipAlbumNames.isEmpty()) {
            return false;
        }
        List<ImmichAlbum> albums = immichClient.listAlbumsByAsset(assetId);
        for (ImmichAlbum album : albums) {
            boolean idMatch = skipAlbumIds.contains(album.id());
            boolean nameMatch = album.albumName() != null
                    && skipAlbumNames.contains(album.albumName().toLowerCase(Locale.ROOT));
            if (idMatch || nameMatch) {
                log.info("资产所在相簿命中跳过配置 assetId={}, album={}({})",
                        assetId, album.albumName(), album.id());
                return true;
            }
        }
        return false;
    }

    private UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void processAssetSafely(ImmichAsset asset, Taxonomy taxonomy, TagIndex tagIndex,
                                    boolean force, boolean dryRun, ProcessingSummary summary) {
        long assetStartedAt = System.nanoTime();
        log.info("开始处理图片，{}", assetPageUrl(asset.id()));
        try {
            if (isInSkippedAlbum(asset.id())) {
                summary.skipped();
                log.info("图片属于跳过相簿，跳过处理 assetId={}", asset.id());
                return;
            }
            if (!force && stateStore.isSuccessfullyProcessed(asset.id())) {
                summary.skipped();
                log.info("图片已设置过标签，按历史记录跳过处理 assetId={}", asset.id());
                return;
            }
            log.info("正在读取图片详情 assetId={}", asset.id());
            AssetDetail detail = immichClient.getAsset(asset.id());
            if (!detail.isImage()) {
                summary.skipped();
                log.info("非图片资产，跳过处理 assetId={}, type={}", asset.id(), detail.type());
                return;
            }
            if (!detail.tags().isEmpty()) {
                summary.skipped();
                log.info("图片已存在标签，跳过模型调用 assetId={}, tagCount={}", asset.id(), detail.tags().size());
                return;
            }
            processOne(asset, taxonomy, tagIndex, dryRun, summary);
            log.info("图片处理流程结束 assetId={}, elapsedMs={}", asset.id(), elapsedMillis(assetStartedAt));
        } catch (Exception e) {
            summary.failed();
            String errorCode = errorCode(e);
            recordFailure(asset, taxonomy, errorCode, dryRun);
            log.error("处理图片失败 assetId={}, errorCode={}, reason={}, elapsedMs={}",
                    asset.id(), errorCode, failureReason(e), elapsedMillis(assetStartedAt));
        }
    }

    private void processOne(ImmichAsset asset, Taxonomy taxonomy, TagIndex tagIndex,
                            boolean dryRun, ProcessingSummary summary) {
        long previewStartedAt = System.nanoTime();
        log.info("正在下载图片预览图 assetId={}", asset.id());
        byte[] preview = immichClient.downloadPreview(asset.id());
        log.info("图片预览图下载完成 assetId={}, imageBytes={}, elapsedMs={}",
                asset.id(), preview.length, elapsedMillis(previewStartedAt));

        ImageAnalysis normalized = analyzeAndNormalizeWithValidationRetries(asset.id(), preview, taxonomy);
        summary.analyzed();
        log.info("模型结果校验完成 assetId={}，接受的标签={}",
                asset.id(), formatTags(normalized.tags()));

        if (dryRun) {
            log.info("dry-run 分析完成，不写入 Immich assetId={}, descriptionLength={}, tags={}", asset.id(),
                    normalized.description().length(), normalized.tags().size());
            return;
        }

        log.info("正在增量写回 Immich assetId={}", asset.id());
        AssetDetail latestDetail = immichClient.getAsset(asset.id());
        if (!latestDetail.tags().isEmpty()) {
            summary.skipped();
            log.info("写回前检测到图片已存在标签，跳过写回 assetId={}, tagCount={}, tags={}", asset.id(),
                    latestDetail.tags().size(), displayTagPaths(latestDetail.tags(), tagIndex));
            return;
        }

        boolean descriptionUpdated = latestDetail.description() == null || latestDetail.description().isBlank();
        if (descriptionUpdated) {
            immichClient.updateDescription(asset.id(), normalized.description());
            summary.descriptionUpdated();
        }

        Set<String> existingPaths = existingTagPaths(latestDetail.tags(), tagIndex);
        Set<UUID> newTagIds = new HashSet<>();
        List<String> newTagPaths = new ArrayList<>();
        for (GeneratedTag generatedTag : normalized.tags()) {
            TagPath path = generatedTag.tagPath();
            String pathText = path.toString();
            if (existingPaths.contains(pathText)) {
                continue;
            }
            UUID leafTagId;
            synchronized (tagIndex) {
                leafTagId = immichClient.ensureTagPath(path, tagIndex);
            }
            newTagIds.add(leafTagId);
            newTagPaths.add(pathText);
            existingPaths.add(pathText);
        }
        if (!newTagIds.isEmpty()) {
            immichClient.attachTags(asset.id(), newTagIds);
            summary.tagsAdded(newTagIds.size());
        }

        stateStore.appendSuccess(asset.id(), fileModifiedAt(asset, latestDetail), visionModelClient.modelName(),
                properties.getPromptVersion(), taxonomy.version());
        if (newTagPaths.stream().anyMatch(e -> !e.startsWith("风光") && !e.startsWith("人像"))) {
            log.info("asset id:{}", asset.id());
        }
        log.info("Immich 写回完成 assetId={}, descriptionUpdated={}, newTagCount={}, newTags={}", asset.id(),
                descriptionUpdated, newTagIds.size(), newTagPaths);
    }

    private ImageAnalysis analyzeWithProgress(UUID assetId, byte[] preview, Taxonomy taxonomy) {
        long startedAt = System.nanoTime();
        CountDownLatch completed = new CountDownLatch(1);

        log.info("开始调用视觉模型 assetId={}, model={}", assetId, visionModelClient.modelName());
        try {
            ImageAnalysis analysis = visionModelClient.analyze(preview, taxonomy);
            log.info("模型结果推理完成 assetId={}, model={}, elapsedMs={}, portraitSubject={}, description={}, tags={}",
                    assetId, visionModelClient.modelName(), elapsedMillis(startedAt), analysis.portraitSubject(),
                    sanitizeLogText(analysis.description()), formatTags(analysis.tags()));
            return analysis;
        } catch (RuntimeException e) {
            log.warn("视觉模型推理未成功 assetId={}, model={}, elapsedMs={}",
                    assetId, visionModelClient.modelName(), elapsedMillis(startedAt));
            throw e;
        } finally {
            completed.countDown();
        }
    }

    private ImageAnalysis analyzeAndNormalizeWithValidationRetries(UUID assetId, byte[] preview,
                                                                    Taxonomy taxonomy) {
        for (int retry = 0; ; retry++) {
            try {
                return normalize(analyzeWithProgress(assetId, preview, taxonomy), taxonomy);
            } catch (IllegalArgumentException e) {
                String reason = failureReason(e);
                if (retry >= MAX_MODEL_VALIDATION_RETRIES) {
                    log.warn("模型结果校验失败，重试机会已耗尽 assetId={}, attempts={}, maxRetries={}, reason={}",
                            assetId, retry + 1, MAX_MODEL_VALIDATION_RETRIES, reason);
                    throw e;
                }
                log.warn("模型结果校验失败，准备重新推理 assetId={}, retry={}/{}, reason={}",
                        assetId, retry + 1, MAX_MODEL_VALIDATION_RETRIES, reason);
            }
        }
    }

    private ImageAnalysis normalize(ImageAnalysis analysis, Taxonomy taxonomy) {
        if (analysis.description() == null || analysis.description().isBlank()
                || analysis.description().length() > 300) {
            throw new IllegalArgumentException("模型描述为空或长度超过限制");
        }

        List<GeneratedTag> tags = analysis.tags();
        Set<String> specificSiblingParentPaths = specificSiblingParentPaths(tags, taxonomy);
        Set<String> acceptedPaths = new HashSet<>();
        List<GeneratedTag> acceptedTags = new ArrayList<>();
        List<String> filteredTags = new ArrayList<>();
        for (GeneratedTag tag : tags) {
            TagPath path;
            try {
                path = tag.tagPath();
            } catch (IllegalArgumentException e) {
                filteredTags.add(formatFilteredTag(tag, "路径格式无效: " + failureReason(e)));
                continue;
            }
            if (!tag.hasMatchingParentTag()) {
                filteredTags.add(formatFilteredTag(tag, "父级标签与标签路径不一致"));
                continue;
            }
            if (!taxonomy.isAllowedRoot(path.root())) {
                filteredTags.add(formatFilteredTag(tag, "一级分类不在预设范围中"));
                continue;
            }
            if (!taxonomy.isAllowed(path)) {
                filteredTags.add(formatFilteredTag(tag, "不在受控词表中"));
                continue;
            }
            if (tag.confidence() == null) {
                filteredTags.add(formatFilteredTag(tag, "置信度为空"));
                continue;
            }
            if (isBelowConfidenceThreshold(tag)
                    && !isFallbackOtherTag(path, specificSiblingParentPaths)) {
                filteredTags.add(formatFilteredTag(tag, "置信度低于阈值 "
                        + properties.getConfidenceThreshold().toPlainString()));
                continue;
            }
            if (!acceptedPaths.add(path.key())) {
                filteredTags.add(formatFilteredTag(tag, "路径重复"));
                continue;
            }
            if (acceptedTags.size() >= MAX_TAGS) {
                filteredTags.add(formatFilteredTag(tag, "超过 " + MAX_TAGS + " 个标签上限"));
                continue;
            }
            acceptedTags.add(tag);
        }

        try {
            validatePortraitTags(analysis.portraitSubject(), acceptedTags, taxonomy);
        } catch (IllegalArgumentException e) {
            if (filteredTags.isEmpty()) {
                throw e;
            }
            throw new IllegalArgumentException(failureReason(e) + "；已过滤标签: "
                    + String.join("，", filteredTags), e);
        }
        return new ImageAnalysis(analysis.description().trim(), acceptedTags, analysis.portraitSubject());
    }

    private Set<String> specificSiblingParentPaths(List<GeneratedTag> tags, Taxonomy taxonomy) {
        Set<String> parentPaths = new HashSet<>();
        for (GeneratedTag tag : tags) {
            try {
                TagPath path = tag.tagPath();
                if (taxonomy.isAllowed(path) && !"其它".equals(path.segments().getLast())) {
                    parentPaths.add(parentPathKey(path));
                }
            } catch (IllegalArgumentException ignored) {
                // 非法路径由主归一化流程记录具体过滤原因。
            }
        }
        return parentPaths;
    }

    private boolean isBelowConfidenceThreshold(GeneratedTag tag) {
        return tag.confidence().compareTo(properties.getConfidenceThreshold()) < 0;
    }

    private boolean isFallbackOtherTag(TagPath path, Set<String> specificSiblingParentPaths) {
        return "其它".equals(path.segments().getLast())
                && !specificSiblingParentPaths.contains(parentPathKey(path));
    }

    private String parentPathKey(TagPath path) {
        return String.join("/", path.segments().subList(0, path.segments().size() - 1));
    }

    private void validatePortraitTags(boolean portraitSubject, List<GeneratedTag> tags, Taxonomy taxonomy) {
        List<TagPath> paths = tags.stream().map(GeneratedTag::tagPath).toList();
        boolean hasPortraitTag = paths.stream().anyMatch(path -> "人像".equals(path.root()));
        if (!portraitSubject && hasPortraitTag) {
            throw new IllegalArgumentException("非人像模型结果包含人像标签");
        }
        if (!portraitSubject) {
            return;
        }
        taxonomy.validateRequiredBranches("人像", paths);
    }

    private Set<String> existingTagPaths(Collection<com.xiwang.phototagautogen.domain.ImmichTag> tags,
                                         TagIndex tagIndex) {
        Set<String> paths = new HashSet<>();
        for (var tag : tags) {
            String path = tagIndex.pathOf(tag.id());
            if (path != null) {
                paths.add(path);
            }
        }
        return paths;
    }

    private List<String> displayTagPaths(Collection<com.xiwang.phototagautogen.domain.ImmichTag> tags,
                                         TagIndex tagIndex) {
        return tags.stream()
                .map(tag -> {
                    String path = tagIndex.pathOf(tag.id());
                    return path == null ? tag.name() : path;
                })
                .sorted()
                .toList();
    }

    private void recordFailure(ImmichAsset asset, Taxonomy taxonomy, String errorCode, boolean dryRun) {
        if (!dryRun) {
            stateStore.appendFailure(asset.id(), fileModifiedAt(asset, null), visionModelClient.modelName(),
                    properties.getPromptVersion(), taxonomy.version(), errorCode);
        }
    }

    private String errorCode(Exception e) {
        if (e instanceof RemoteCallException remoteCallException) {
            return "http-" + remoteCallException.statusCode();
        }
        if (e instanceof IllegalArgumentException) {
            return "invalid-model-result";
        }
        return "processing-error";
    }

    private String fileModifiedAt(ImmichAsset asset, AssetDetail detail) {
        Instant instant = asset.fileModifiedAt() != null ? asset.fileModifiedAt()
                : detail == null ? null : detail.fileModifiedAt();
        return instant == null ? "" : instant.toString();
    }

    private String assetPageUrl(UUID assetId) {
        return immichBaseUrl + "/photos/" + assetId;
    }

    private String formatTags(Collection<GeneratedTag> tags) {
        return tags.stream()
                .map(tag -> tag.path().stream()
                        .map(this::sanitizeLogText)
                        .collect(Collectors.joining("/"))
                        + "(" + (tag.confidence() == null ? "null" : tag.confidence().toPlainString()) + ")")
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private String formatFilteredTag(GeneratedTag tag, String reason) {
        String path = tag.path().stream()
                .map(this::sanitizeLogText)
                .collect(Collectors.joining("/"));
        return path + "（" + sanitizeLogText(reason) + "）";
    }

    private String failureReason(Exception exception) {
        String message = sanitizeLogText(exception.getMessage());
        return message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private String sanitizeLogText(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sanitized = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> sanitized.appendCodePoint(
                Character.isISOControl(codePoint) ? ' ' : codePoint));
        return sanitized.toString().trim();
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    private int processInBatches(ExecutorService executor, List<ImmichAsset> assets, Taxonomy taxonomy,
                                 TagIndex tagIndex, boolean force, boolean dryRun, ProcessingSummary summary,
                                 int processedSinceModelRelease) {
        int start = 0;
        while (start < assets.size()) {
            int remainingBeforeRelease = properties.getModelReleaseInterval() - processedSinceModelRelease;
            int end = Math.min(start + remainingBeforeRelease, assets.size());
            List<Future<?>> futures = new ArrayList<>(end - start);
            for (ImmichAsset asset : assets.subList(start, end)) {
                futures.add(executor.submit(() -> processAssetSafely(asset, taxonomy, tagIndex, force, dryRun, summary)));
            }
            awaitCompletion(futures);
            processedSinceModelRelease += end - start;
            start = end;
            if (processedSinceModelRelease >= properties.getModelReleaseInterval()) {
                if (visionModelClient.supportsResourceRelease()) {
                    releaseModelResources(processedSinceModelRelease);
                }
                processedSinceModelRelease = 0;
            }
        }
        return processedSinceModelRelease;
    }

    private void releaseModelResources(int processedCount) {
        try {
            visionModelClient.releaseResources();
            log.info("已释放视觉模型资源 processedSinceLastRelease={}", processedCount);
        } catch (RuntimeException e) {
            log.warn("释放视觉模型资源失败，将继续处理剩余图片 processedSinceLastRelease={}, model={}",
                    processedCount, visionModelClient.modelName());
        }
    }

    private void awaitCompletion(List<Future<?>> futures) {
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待图片处理任务时被中断", e);
            } catch (ExecutionException e) {
                throw new IllegalStateException("图片处理任务异常退出", e.getCause());
            }
        }
    }
}
