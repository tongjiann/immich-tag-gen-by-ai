package com.xiwang.phototagautogen.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiwang.phototagautogen.config.ProcessingProperties;
import com.xiwang.phototagautogen.domain.AssetDetail;
import com.xiwang.phototagautogen.domain.ImmichAlbum;
import com.xiwang.phototagautogen.domain.ImmichTag;
import com.xiwang.phototagautogen.domain.ProcessingState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JsonlStateStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void 追加状态后可读取最新结果() {
        ProcessingProperties properties = new ProcessingProperties();
        properties.setStateFile(tempDir.resolve("state.jsonl").toString());
        JsonlStateStore store = new JsonlStateStore(new ObjectMapper().findAndRegisterModules(), properties);
        UUID assetId = UUID.randomUUID();

        store.appendSuccess(assetId, Instant.now().toString(), "qwen2.5vl:7b", "v1", 1);

        ProcessingState state = store.find(assetId);
        assertThat(state).isNotNull();
        assertThat(state.status()).isEqualTo("SUCCESS");
        assertThat(state.model()).isEqualTo("qwen2.5vl:7b");
    }

    @Test
    void 成功状态存在时应判定为已处理() {
        ProcessingProperties properties = new ProcessingProperties();
        properties.setStateFile(tempDir.resolve("processed-state.jsonl").toString());
        JsonlStateStore store = new JsonlStateStore(new ObjectMapper().findAndRegisterModules(), properties);
        UUID assetId = UUID.randomUUID();

        store.appendSuccess(assetId, Instant.now().toString(), "qwen2.5vl:7b", "v1", 1);

        assertThat(store.isSuccessfullyProcessed(assetId)).isTrue();
    }

    @Test
    void 无状态或失败状态时不应判定为已处理() {
        ProcessingProperties properties = new ProcessingProperties();
        properties.setStateFile(tempDir.resolve("not-processed-state.jsonl").toString());
        JsonlStateStore store = new JsonlStateStore(new ObjectMapper().findAndRegisterModules(), properties);
        UUID assetId = UUID.randomUUID();

        assertThat(store.isSuccessfullyProcessed(assetId)).isFalse();

        store.appendFailure(assetId, Instant.now().toString(), "qwen2.5vl:7b", "v1", 1, "http-504");

        assertThat(store.isSuccessfullyProcessed(assetId)).isFalse();
    }
    @Test
    void 图片详情和相簿读取结果可按文件修改时间复用() {
        ProcessingProperties properties = new ProcessingProperties();
        properties.setStateFile(tempDir.resolve("read-cache-state.jsonl").toString());
        JsonlStateStore store = new JsonlStateStore(new ObjectMapper().findAndRegisterModules(), properties);
        UUID assetId = UUID.randomUUID();
        UUID tagId = UUID.randomUUID();
        UUID albumId = UUID.randomUUID();
        String fileModifiedAt = "2026-08-19T00:00:00Z";
        AssetDetail detail = new AssetDetail(assetId, "IMAGE", "已有描述",
                Instant.parse(fileModifiedAt), List.of(new ImmichTag(tagId, "春", null)));
        List<ImmichAlbum> albums = List.of(new ImmichAlbum(albumId, "旅行相册", List.of(assetId)));

        store.appendRead(assetId, fileModifiedAt, detail, albums, true, "qwen2.5vl:7b", "v4", 3);

        ProcessingState state = store.findReadState(assetId, fileModifiedAt);
        assertThat(state).isNotNull();
        assertThat(state.cachedAssetDetail(assetId, "IMAGE", null).description()).isEqualTo("已有描述");
        assertThat(state.cachedAssetDetail(assetId, "IMAGE", null).tags()).hasSize(1);
        assertThat(state.albums()).extracting(ImmichAlbum::id, ImmichAlbum::albumName)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(albumId, "旅行相册"));
        assertThat(store.findReadState(assetId, "2026-08-20T00:00:00Z")).isNull();
    }

}
