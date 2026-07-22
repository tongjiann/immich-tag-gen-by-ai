package com.xiwang.phototagautogen.service;

import com.xiwang.phototagautogen.client.ImmichClient;
import com.xiwang.phototagautogen.client.RemoteCallException;
import com.xiwang.phototagautogen.client.VisionModelClient;
import com.xiwang.phototagautogen.config.ImmichProperties;
import com.xiwang.phototagautogen.config.ProcessingProperties;
import com.xiwang.phototagautogen.domain.AssetDetail;
import com.xiwang.phototagautogen.domain.AssetPage;
import com.xiwang.phototagautogen.domain.GeneratedTag;
import com.xiwang.phototagautogen.domain.ImageAnalysis;
import com.xiwang.phototagautogen.domain.ImmichAsset;
import com.xiwang.phototagautogen.domain.ImmichTag;
import com.xiwang.phototagautogen.domain.ProcessingSummary;
import com.xiwang.phototagautogen.domain.TagIndex;
import com.xiwang.phototagautogen.domain.Taxonomy;
import com.xiwang.phototagautogen.state.JsonlStateStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class PhotoProcessingServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void 已有人工描述时只补充标签且第二次运行跳过推理(CapturedOutput output) {
        ImmichClient immichClient = mock(ImmichClient.class);
        VisionModelClient modelClient = mock(VisionModelClient.class);
        UUID assetId = UUID.randomUUID();
        Instant modifiedAt = Instant.parse("2026-07-21T00:00:00Z");
        ImmichAsset asset = new ImmichAsset(assetId, "IMAGE", false, false, modifiedAt);
        AssetDetail detail = new AssetDetail(assetId, "IMAGE", "人工描述", modifiedAt, List.of());
        Taxonomy taxonomy = new Taxonomy(1, Map.of("季节", Map.of("", List.of("春"))));
        ProcessingProperties properties = properties(tempDir.resolve("state.jsonl"));
        JsonlStateStore stateStore = new JsonlStateStore(new ObjectMapper().findAndRegisterModules(), properties);
        TaxonomyLoader taxonomyLoader = mock(TaxonomyLoader.class);
        when(taxonomyLoader.load()).thenReturn(taxonomy);
        when(modelClient.modelName()).thenReturn("qwen2.5vl:7b");
        when(immichClient.listTags()).thenReturn(new TagIndex());
        when(immichClient.listImages(1, 100)).thenReturn(new AssetPage(List.of(asset), false));
        when(immichClient.getAsset(assetId)).thenReturn(detail);
        when(immichClient.downloadPreview(assetId)).thenReturn(new byte[] {1, 2, 3});
        when(modelClient.analyze(any(), eq(taxonomy))).thenReturn(new ImageAnalysis(
                "模型描述", List.of(new GeneratedTag(List.of("季节", "春"), new BigDecimal("0.95"))), false));
        when(immichClient.ensureTagPath(any(), any())).thenReturn(UUID.randomUUID());

        PhotoProcessingService service = new PhotoProcessingService(immichClient, modelClient, taxonomyLoader,
                stateStore, properties, immichProperties());

        ProcessingSummary first = service.run(false, false, null);
        ProcessingSummary second = service.run(false, false, null);

        assertThat(first.failures()).isZero();
        assertThat(second.failures()).isZero();
        verify(immichClient, never()).updateDescription(any(), any());
        verify(immichClient, times(1)).attachTags(eq(assetId), any());
        verify(modelClient, times(1)).analyze(any(), eq(taxonomy));
        assertThat(output).contains(
                "开始处理图片，http://immich.test/photos/" + assetId,
                "description=模型描述",
                "tags=[季节/春(0.95)]",
                "模型结果校验完成 assetId=" + assetId + "，接受的标签=[季节/春(0.95)]");
    }

    @Test
    void 图片已有标签时应在下载预览前跳过() {
        ImmichClient immichClient = mock(ImmichClient.class);
        VisionModelClient modelClient = mock(VisionModelClient.class);
        UUID assetId = UUID.randomUUID();
        UUID tagId = UUID.randomUUID();
        Instant modifiedAt = Instant.parse("2026-07-21T00:00:00Z");
        ImmichAsset asset = new ImmichAsset(assetId, "IMAGE", false, false, modifiedAt);
        AssetDetail detail = new AssetDetail(assetId, "IMAGE", null, modifiedAt,
                List.of(new ImmichTag(tagId, "春季", null)));
        Taxonomy taxonomy = new Taxonomy(1, Map.of("季节", Map.of("", List.of("春季"))));
        ProcessingProperties properties = properties(tempDir.resolve("existing-tag-state.jsonl"));
        JsonlStateStore stateStore = new JsonlStateStore(new ObjectMapper().findAndRegisterModules(), properties);
        TaxonomyLoader taxonomyLoader = mock(TaxonomyLoader.class);

        when(taxonomyLoader.load()).thenReturn(taxonomy);
        when(modelClient.modelName()).thenReturn("qwen2.5vl:7b");
        when(immichClient.listTags()).thenReturn(new TagIndex());
        when(immichClient.listImages(1, 100)).thenReturn(new AssetPage(List.of(asset), false));
        when(immichClient.getAsset(assetId)).thenReturn(detail);

        PhotoProcessingService service = new PhotoProcessingService(immichClient, modelClient, taxonomyLoader,
                stateStore, properties, immichProperties());

        ProcessingSummary summary = service.run(false, false, null);

        assertThat(summary.skippedCount()).isEqualTo(1);
        assertThat(summary.analyzedCount()).isZero();
        assertThat(summary.failures()).isZero();
        verify(immichClient, never()).downloadPreview(any());
        verify(modelClient, never()).analyze(any(), any());
    }

    @Test
    void 达到模型释放间隔时应卸载模型并继续处理() {
        ImmichClient immichClient = mock(ImmichClient.class);
        VisionModelClient modelClient = mock(VisionModelClient.class);
        Instant modifiedAt = Instant.parse("2026-07-21T00:00:00Z");
        ImmichAsset firstAsset = new ImmichAsset(UUID.randomUUID(), "IMAGE", false, false, modifiedAt);
        ImmichAsset secondAsset = new ImmichAsset(UUID.randomUUID(), "IMAGE", false, false, modifiedAt);
        ImmichAsset thirdAsset = new ImmichAsset(UUID.randomUUID(), "IMAGE", false, false, modifiedAt);
        Taxonomy taxonomy = new Taxonomy(1, Map.of("季节", Map.of("", List.of("春季"))));
        ProcessingProperties properties = properties(tempDir.resolve("release-state.jsonl"));
        properties.setModelReleaseInterval(2);
        JsonlStateStore stateStore = new JsonlStateStore(new ObjectMapper().findAndRegisterModules(), properties);
        TaxonomyLoader taxonomyLoader = mock(TaxonomyLoader.class);

        when(taxonomyLoader.load()).thenReturn(taxonomy);
        when(modelClient.modelName()).thenReturn("qwen2.5vl:7b");
        when(modelClient.supportsResourceRelease()).thenReturn(true);
        when(immichClient.listTags()).thenReturn(new TagIndex());
        when(immichClient.listImages(1, 100))
                .thenReturn(new AssetPage(List.of(firstAsset, secondAsset, thirdAsset), false));
        when(immichClient.getAsset(any())).thenAnswer(invocation ->
                new AssetDetail(invocation.getArgument(0), "IMAGE", "人工描述", modifiedAt, List.of()));
        when(immichClient.downloadPreview(any())).thenReturn(new byte[] {1});
        when(modelClient.analyze(any(), eq(taxonomy))).thenReturn(new ImageAnalysis("模型描述", List.of(), false));

        PhotoProcessingService service = new PhotoProcessingService(immichClient, modelClient, taxonomyLoader,
                stateStore, properties, immichProperties());

        ProcessingSummary summary = service.run(false, false, null);

        assertThat(summary.analyzedCount()).isEqualTo(3);
        assertThat(summary.failures()).isZero();
        verify(modelClient, times(1)).releaseResources();
    }

    @Test
    void 低置信度和不在词表中的标签不会写入() {
        ImmichClient immichClient = mock(ImmichClient.class);
        VisionModelClient modelClient = mock(VisionModelClient.class);
        UUID assetId = UUID.randomUUID();
        Instant modifiedAt = Instant.parse("2026-07-21T00:00:00Z");
        ImmichAsset asset = new ImmichAsset(assetId, "IMAGE", false, false, modifiedAt);
        AssetDetail detail = new AssetDetail(assetId, "IMAGE", null, modifiedAt, List.of());
        Taxonomy taxonomy = new Taxonomy(1, Map.of("季节", Map.of("", List.of("春"))));
        ProcessingProperties properties = properties(tempDir.resolve("state.jsonl"));
        JsonlStateStore stateStore = new JsonlStateStore(new ObjectMapper().findAndRegisterModules(), properties);
        TaxonomyLoader taxonomyLoader = mock(TaxonomyLoader.class);
        when(taxonomyLoader.load()).thenReturn(taxonomy);
        when(modelClient.modelName()).thenReturn("qwen2.5vl:7b");
        when(immichClient.listTags()).thenReturn(new TagIndex());
        when(immichClient.listImages(1, 100)).thenReturn(new AssetPage(List.of(asset), false));
        when(immichClient.getAsset(assetId)).thenReturn(detail);
        when(immichClient.downloadPreview(assetId)).thenReturn(new byte[] {1});
        when(modelClient.analyze(any(), eq(taxonomy))).thenReturn(new ImageAnalysis(
                "模型描述", List.of(
                new GeneratedTag(List.of("季节", "春"), new BigDecimal("0.64")),
                new GeneratedTag(List.of("季节", "未知"), new BigDecimal("0.99"))), false));

        PhotoProcessingService service = new PhotoProcessingService(immichClient, modelClient, taxonomyLoader,
                stateStore, properties, immichProperties());

        ProcessingSummary summary = service.run(false, false, null);

        assertThat(summary.failures()).isZero();
        verify(immichClient).updateDescription(assetId, "模型描述");
        verify(immichClient, never()).ensureTagPath(any(), any());
        verify(immichClient, never()).attachTags(any(), any());
    }


    @Test
    void 配置两个并发线程时应同时执行两张图片推理() {
        ImmichClient immichClient = mock(ImmichClient.class);
        VisionModelClient modelClient = mock(VisionModelClient.class);
        Instant modifiedAt = Instant.parse("2026-07-21T00:00:00Z");
        ImmichAsset firstAsset = new ImmichAsset(UUID.randomUUID(), "IMAGE", false, false, modifiedAt);
        ImmichAsset secondAsset = new ImmichAsset(UUID.randomUUID(), "IMAGE", false, false, modifiedAt);
        Taxonomy taxonomy = new Taxonomy(1, Map.of("季节", Map.of("", List.of("春季"))));
        ProcessingProperties properties = properties(tempDir.resolve("concurrent-state.jsonl"));
        properties.setConcurrency(2);
        JsonlStateStore stateStore = new JsonlStateStore(new ObjectMapper().findAndRegisterModules(), properties);
        TaxonomyLoader taxonomyLoader = mock(TaxonomyLoader.class);
        CountDownLatch bothStarted = new CountDownLatch(2);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();

        when(taxonomyLoader.load()).thenReturn(taxonomy);
        when(modelClient.modelName()).thenReturn("qwen2.5vl:7b");
        when(immichClient.listTags()).thenReturn(new TagIndex());
        when(immichClient.listImages(1, 100)).thenReturn(new AssetPage(List.of(firstAsset, secondAsset), false));
        when(immichClient.getAsset(any())).thenAnswer(invocation ->
                new AssetDetail(invocation.getArgument(0), "IMAGE", "人工描述", modifiedAt, List.of()));
        when(immichClient.downloadPreview(any())).thenReturn(new byte[] {1, 2, 3});
        when(modelClient.analyze(any(), eq(taxonomy))).thenAnswer(invocation -> {
            int current = active.incrementAndGet();
            maxActive.accumulateAndGet(current, Math::max);
            bothStarted.countDown();
            try {
                if (!bothStarted.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("图片推理未并发执行");
                }
                return new ImageAnalysis("模型描述", List.of(), false);
            } finally {
                active.decrementAndGet();
            }
        });

        PhotoProcessingService service = new PhotoProcessingService(immichClient, modelClient, taxonomyLoader,
                stateStore, properties, immichProperties());

        ProcessingSummary summary = service.run(false, false, null);

        assertThat(summary.failures()).isZero();
        assertThat(summary.analyzedCount()).isEqualTo(2);
        assertThat(maxActive).hasValue(2);
    }

    @Test
    void 单图模式传入视频时应跳过预览和模型调用() {
        ImmichClient immichClient = mock(ImmichClient.class);
        VisionModelClient modelClient = mock(VisionModelClient.class);
        UUID assetId = UUID.randomUUID();
        Instant modifiedAt = Instant.parse("2026-07-21T00:00:00Z");
        Taxonomy taxonomy = new Taxonomy(1, Map.of("季节", Map.of("", List.of("春季"))));
        ProcessingProperties properties = properties(tempDir.resolve("single-video-state.jsonl"));
        JsonlStateStore stateStore = new JsonlStateStore(new ObjectMapper().findAndRegisterModules(), properties);
        TaxonomyLoader taxonomyLoader = mock(TaxonomyLoader.class);

        when(taxonomyLoader.load()).thenReturn(taxonomy);
        when(immichClient.listTags()).thenReturn(new TagIndex());
        when(immichClient.getAsset(assetId)).thenReturn(
                new AssetDetail(assetId, "VIDEO", null, modifiedAt, List.of()));

        PhotoProcessingService service = new PhotoProcessingService(immichClient, modelClient, taxonomyLoader,
                stateStore, properties, immichProperties());

        ProcessingSummary summary = service.run(false, false, assetId);

        assertThat(summary.scannedCount()).isEqualTo(1);
        assertThat(summary.skippedCount()).isEqualTo(1);
        assertThat(summary.analyzedCount()).isZero();
        assertThat(summary.failures()).isZero();
        verify(immichClient, never()).downloadPreview(any());
        verify(immichClient, never()).updateDescription(any(), any());
        verify(immichClient, never()).attachTags(any(), any());
        verify(modelClient, never()).analyze(any(), any());
    }

    @Test
    void 单图处理失败时记录失败并返回汇总() {
        ImmichClient immichClient = mock(ImmichClient.class);
        VisionModelClient modelClient = mock(VisionModelClient.class);
        UUID assetId = UUID.randomUUID();
        Taxonomy taxonomy = new Taxonomy(1, Map.of("季节", Map.of("", List.of("春季"))));
        ProcessingProperties properties = properties(tempDir.resolve("single-failure-state.jsonl"));
        JsonlStateStore stateStore = new JsonlStateStore(new ObjectMapper().findAndRegisterModules(), properties);
        TaxonomyLoader taxonomyLoader = mock(TaxonomyLoader.class);
        when(taxonomyLoader.load()).thenReturn(taxonomy);
        when(modelClient.modelName()).thenReturn("qwen2.5vl:7b");
        when(immichClient.listTags()).thenReturn(new TagIndex());
        when(immichClient.getAsset(assetId)).thenThrow(new IllegalStateException("不可访问"));

        PhotoProcessingService service = new PhotoProcessingService(immichClient, modelClient, taxonomyLoader,
                stateStore, properties, immichProperties());

        ProcessingSummary summary = service.run(false, false, assetId);

        assertThat(summary.scannedCount()).isEqualTo(1);
        assertThat(summary.failures()).isEqualTo(1);
        assertThat(stateStore.find(assetId).status()).isEqualTo("FAILED");
    }


    @Test
    void 人像主题必选分类齐全时应写入标签() {
        Scenario scenario = runSingleAnalysis(
                new ImageAnalysis("完整人像描述", completePortraitTags(), true),
                "complete-portrait-state.jsonl");

        assertThat(scenario.summary().failures()).isZero();
        assertThat(scenario.summary().analyzedCount()).isEqualTo(1);
        verify(scenario.immichClient(), times(8)).ensureTagPath(any(), any());
        verify(scenario.immichClient()).attachTags(eq(scenario.assetId()), any());
        assertThat(scenario.stateStore().find(scenario.assetId()).status()).isEqualTo("SUCCESS");
    }

    @Test
    void 人像校验首次失败后重试成功时应正常写入(CapturedOutput output) {
        List<GeneratedTag> incompleteTags = completePortraitTags().stream()
                .filter(tag -> !tag.path().get(1).equals("拍摄风格"))
                .toList();
        Scenario scenario = runSingleAnalyses(List.of(
                        new ImageAnalysis("首次缺少拍摄风格", incompleteTags, true),
                        new ImageAnalysis("重试后的完整人像描述", completePortraitTags(), true)),
                "portrait-retry-success-state.jsonl");

        assertThat(scenario.summary().failures()).isZero();
        assertThat(scenario.summary().analyzedCount()).isEqualTo(1);
        verify(scenario.modelClient(), times(2)).analyze(any(), any());
        verify(scenario.immichClient()).updateDescription(scenario.assetId(), "重试后的完整人像描述");
        verify(scenario.immichClient()).attachTags(eq(scenario.assetId()), any());
        assertThat(output).contains(
                "模型结果校验失败，准备重新推理 assetId=" + scenario.assetId(),
                "retry=1/3",
                "reason=人像模型结果缺少必选分类: 拍摄风格");
    }

    @Test
    void 人像校验连续失败时应重试三次并输出具体原因(CapturedOutput output) {
        List<GeneratedTag> tags = completePortraitTags().stream()
                .filter(tag -> !List.of("人脸角度", "拍摄风格").contains(tag.path().get(1)))
                .collect(Collectors.toCollection(ArrayList::new));
        tags.add(new GeneratedTag(List.of("人像", "人脸角 度", "正脸"), new BigDecimal("0.98")));
        Scenario scenario = runSingleAnalysis(
                new ImageAnalysis("包含非法路径且缺少必选分类的人像描述", tags, true),
                "missing-portrait-state.jsonl");

        assertThat(scenario.summary().failures()).isEqualTo(1);
        assertThat(scenario.summary().analyzedCount()).isZero();
        verify(scenario.modelClient(), times(4)).analyze(any(), any());
        verify(scenario.immichClient(), never()).updateDescription(any(), any());
        verify(scenario.immichClient(), never()).ensureTagPath(any(), any());
        verify(scenario.immichClient(), never()).attachTags(any(), any());
        assertThat(scenario.stateStore().find(scenario.assetId()).status()).isEqualTo("FAILED");
        assertThat(output).contains(
                "retry=1/3",
                "retry=2/3",
                "retry=3/3",
                "模型结果校验失败，重试机会已耗尽 assetId=" + scenario.assetId(),
                "attempts=4, maxRetries=3",
                "reason=人像模型结果缺少必选分类: 人脸角度、拍摄风格；已过滤标签: "
                        + "人像/人脸角 度/正脸（不在受控词表中）",
                "处理图片失败 assetId=" + scenario.assetId() + ", errorCode=invalid-model-result, reason=");
    }

    @Test
    void 人像必选标签低于置信度阈值时应拒绝全部写入() {
        List<GeneratedTag> tags = new ArrayList<>(completePortraitTags());
        tags.set(6, tag("场景", "街道", "0.64"));
        Scenario scenario = runSingleAnalysis(
                new ImageAnalysis("低置信度人像描述", tags, true),
                "low-confidence-portrait-state.jsonl");

        assertThat(scenario.summary().failures()).isEqualTo(1);
        verify(scenario.immichClient(), never()).updateDescription(any(), any());
        verify(scenario.immichClient(), never()).attachTags(any(), any());
        assertThat(scenario.stateStore().find(scenario.assetId()).status()).isEqualTo("FAILED");
    }

    @Test
    void 模型远程调用异常时不应触发校验重试(CapturedOutput output) {
        ImmichClient immichClient = mock(ImmichClient.class);
        VisionModelClient modelClient = mock(VisionModelClient.class);
        UUID assetId = UUID.randomUUID();
        Instant modifiedAt = Instant.parse("2026-07-21T00:00:00Z");
        ImmichAsset asset = new ImmichAsset(assetId, "IMAGE", false, false, modifiedAt);
        AssetDetail detail = new AssetDetail(assetId, "IMAGE", null, modifiedAt, List.of());
        Taxonomy taxonomy = new TaxonomyLoader().load();
        ProcessingProperties properties = properties(tempDir.resolve("remote-model-failure-state.jsonl"));
        JsonlStateStore stateStore = new JsonlStateStore(new ObjectMapper().findAndRegisterModules(), properties);
        TaxonomyLoader taxonomyLoader = mock(TaxonomyLoader.class);

        when(taxonomyLoader.load()).thenReturn(taxonomy);
        when(modelClient.modelName()).thenReturn("qwen2.5vl:7b");
        when(modelClient.analyze(any(), eq(taxonomy)))
                .thenThrow(new RemoteCallException("视觉模型请求超时", 504));
        when(immichClient.listTags()).thenReturn(new TagIndex());
        when(immichClient.listImages(1, 100)).thenReturn(new AssetPage(List.of(asset), false));
        when(immichClient.getAsset(assetId)).thenReturn(detail);
        when(immichClient.downloadPreview(assetId)).thenReturn(new byte[] {1, 2, 3});

        PhotoProcessingService service = new PhotoProcessingService(immichClient, modelClient, taxonomyLoader,
                stateStore, properties, immichProperties());
        ProcessingSummary summary = service.run(false, false, null);

        assertThat(summary.failures()).isEqualTo(1);
        verify(modelClient).analyze(any(), eq(taxonomy));
        verify(immichClient, never()).updateDescription(any(), any());
        verify(immichClient, never()).attachTags(any(), any());
        assertThat(output).contains(
                "处理图片失败 assetId=" + assetId,
                "errorCode=http-504",
                "reason=视觉模型请求超时")
                .doesNotContain("模型结果校验失败，准备重新推理 assetId=" + assetId);
    }

    @Test
    void 非人像声明包含人像标签时应拒绝全部写入() {
        Scenario scenario = runSingleAnalysis(
                new ImageAnalysis("错误的人像声明", completePortraitTags(), false),
                "false-portrait-state.jsonl");

        assertThat(scenario.summary().failures()).isEqualTo(1);
        verify(scenario.immichClient(), never()).updateDescription(any(), any());
        verify(scenario.immichClient(), never()).attachTags(any(), any());
    }

    @Test
    void 非人像主题应正常写入通用标签() {
        Scenario scenario = runSingleAnalysis(
                new ImageAnalysis("春季风景描述", List.of(
                        new GeneratedTag(List.of("季节", "春"), new BigDecimal("0.95"))), false),
                "non-portrait-state.jsonl");

        assertThat(scenario.summary().failures()).isZero();
        assertThat(scenario.summary().analyzedCount()).isEqualTo(1);
        verify(scenario.immichClient()).ensureTagPath(any(), any());
        verify(scenario.immichClient()).attachTags(eq(scenario.assetId()), any());
    }

    private Scenario runSingleAnalysis(ImageAnalysis analysis, String stateFileName) {
        return runSingleAnalyses(List.of(analysis), stateFileName);
    }

    private Scenario runSingleAnalyses(List<ImageAnalysis> analyses, String stateFileName) {
        ImmichClient immichClient = mock(ImmichClient.class);
        VisionModelClient modelClient = mock(VisionModelClient.class);
        UUID assetId = UUID.randomUUID();
        Instant modifiedAt = Instant.parse("2026-07-21T00:00:00Z");
        ImmichAsset asset = new ImmichAsset(assetId, "IMAGE", false, false, modifiedAt);
        AssetDetail detail = new AssetDetail(assetId, "IMAGE", null, modifiedAt, List.of());
        Taxonomy taxonomy = new TaxonomyLoader().load();
        ProcessingProperties properties = properties(tempDir.resolve(stateFileName));
        JsonlStateStore stateStore = new JsonlStateStore(new ObjectMapper().findAndRegisterModules(), properties);
        TaxonomyLoader taxonomyLoader = mock(TaxonomyLoader.class);
        AtomicInteger analysisIndex = new AtomicInteger();

        when(taxonomyLoader.load()).thenReturn(taxonomy);
        when(modelClient.modelName()).thenReturn("qwen2.5vl:7b");
        when(modelClient.analyze(any(), eq(taxonomy))).thenAnswer(invocation ->
                analyses.get(Math.min(analysisIndex.getAndIncrement(), analyses.size() - 1)));
        when(immichClient.listTags()).thenReturn(new TagIndex());
        when(immichClient.listImages(1, 100)).thenReturn(new AssetPage(List.of(asset), false));
        when(immichClient.getAsset(assetId)).thenReturn(detail);
        when(immichClient.downloadPreview(assetId)).thenReturn(new byte[] {1, 2, 3});
        when(immichClient.ensureTagPath(any(), any())).thenAnswer(invocation -> UUID.randomUUID());

        PhotoProcessingService service = new PhotoProcessingService(immichClient, modelClient, taxonomyLoader,
                stateStore, properties, immichProperties());
        ProcessingSummary summary = service.run(false, false, null);
        return new Scenario(summary, immichClient, modelClient, stateStore, assetId);
    }

    private List<GeneratedTag> completePortraitTags() {
        return List.of(
                tag("人脸角度", "正脸", "0.95"),
                tag("姿态", "站立", "0.95"),
                tag("景别", "全景", "0.95"),
                tag("服饰类型", "休闲装", "0.95"),
                tag("主体颜色", "蓝", "0.95"),
                tag("配饰", "无配饰", "0.95"),
                tag("场景", "街道", "0.95"),
                tag("拍摄风格", "清新", "0.95"));
    }

    private GeneratedTag tag(String branch, String leaf, String confidence) {
        return new GeneratedTag(List.of("人像", branch, leaf), new BigDecimal(confidence));
    }

    private record Scenario(ProcessingSummary summary, ImmichClient immichClient,
                            VisionModelClient modelClient, JsonlStateStore stateStore, UUID assetId) {
    }

    private ImmichProperties immichProperties() {
        ImmichProperties properties = new ImmichProperties();
        properties.setBaseUrl("http://immich.test/");
        properties.setApiKey("test-api-key");
        return properties;
    }

    private ProcessingProperties properties(Path stateFile) {
        ProcessingProperties properties = new ProcessingProperties();
        properties.setStateFile(stateFile.toString());
        return properties;
    }
}
