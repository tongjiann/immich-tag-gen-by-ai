package com.xiwang.phototagautogen.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiwang.phototagautogen.config.ProcessingProperties;
import com.xiwang.phototagautogen.domain.AssetDetail;
import com.xiwang.phototagautogen.domain.ImmichAlbum;
import com.xiwang.phototagautogen.domain.ProcessingState;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class JsonlStateStore {
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_READ = "READ";

    private final ObjectMapper objectMapper;
    private final Path stateFile;
    private final Map<UUID, ProcessingState> latestStates = new HashMap<>();

    public JsonlStateStore(ObjectMapper objectMapper, ProcessingProperties properties) {
        this.objectMapper = objectMapper;
        this.stateFile = Path.of(properties.getStateFile());
        load();
    }

    public synchronized ProcessingState find(UUID assetId) {
        return latestStates.get(assetId);
    }

    public synchronized boolean isSuccessfullyProcessed(UUID assetId) {
        ProcessingState state = latestStates.get(assetId);
        return state != null && STATUS_SUCCESS.equals(state.status());
    }

    public synchronized ProcessingState findReadState(UUID assetId, String fileModifiedAt) {
        ProcessingState state = latestStates.get(assetId);
        return state != null && state.readMatches(fileModifiedAt) ? state : null;
    }

    public synchronized ProcessingState findFileState(UUID assetId, String fileModifiedAt) {
        ProcessingState state = latestStates.get(assetId);
        return state != null && state.fileMatches(fileModifiedAt) ? state : null;
    }

    public synchronized void appendAlbumRead(UUID assetId, String fileModifiedAt, List<ImmichAlbum> albums,
                                              String model, String promptVersion, int taxonomyVersion) {
        append(new ProcessingState(assetId, fileModifiedAt, model, promptVersion, taxonomyVersion,
                STATUS_READ, Instant.now(), null, false, true, null, null, List.of(), cacheAlbums(albums)));
    }

    public synchronized void appendRead(UUID assetId, String fileModifiedAt, AssetDetail detail,
                                         List<ImmichAlbum> albums, boolean albumsRead, String model,
                                         String promptVersion, int taxonomyVersion) {
        append(new ProcessingState(assetId, fileModifiedAt, model, promptVersion, taxonomyVersion,
                STATUS_READ, Instant.now(), null, true, albumsRead, detail.type(), detail.description(),
                detail.tags(), cacheAlbums(albums)));
    }

    public synchronized void appendSuccess(UUID assetId, String fileModifiedAt, String model,
                                            String promptVersion, int taxonomyVersion) {
        appendSuccess(assetId, fileModifiedAt, model, promptVersion, taxonomyVersion,
                null, List.of(), false);
    }

    public synchronized void appendSuccess(UUID assetId, String fileModifiedAt, String model,
                                            String promptVersion, int taxonomyVersion, AssetDetail detail,
                                            List<ImmichAlbum> albums, boolean albumsRead) {
        append(new ProcessingState(assetId, fileModifiedAt, model, promptVersion, taxonomyVersion,
                STATUS_SUCCESS, Instant.now(), null, detail != null, albumsRead,
                detail == null ? null : detail.type(), detail == null ? null : detail.description(),
                detail == null ? List.of() : detail.tags(), cacheAlbums(albums)));
    }

    public synchronized void appendFailure(UUID assetId, String fileModifiedAt, String model,
                                            String promptVersion, int taxonomyVersion, String errorCode) {
        appendFailure(assetId, fileModifiedAt, model, promptVersion, taxonomyVersion, errorCode,
                null, List.of(), false);
    }

    public synchronized void appendFailure(UUID assetId, String fileModifiedAt, String model,
                                            String promptVersion, int taxonomyVersion, String errorCode,
                                            AssetDetail detail, List<ImmichAlbum> albums, boolean albumsRead) {
        append(new ProcessingState(assetId, fileModifiedAt, model, promptVersion, taxonomyVersion,
                "FAILED", Instant.now(), errorCode, detail != null, albumsRead,
                detail == null ? null : detail.type(), detail == null ? null : detail.description(),
                detail == null ? List.of() : detail.tags(), cacheAlbums(albums)));
    }

    private List<ImmichAlbum> cacheAlbums(List<ImmichAlbum> albums) {
        return albums == null ? List.of() : albums.stream()
                .map(album -> new ImmichAlbum(album.id(), album.albumName(), List.of()))
                .toList();
    }

    private void append(ProcessingState state) {
        try {
            Path parent = stateFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (BufferedWriter writer = Files.newBufferedWriter(stateFile, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND)) {
                writer.write(objectMapper.writeValueAsString(state));
                writer.newLine();
            }
            latestStates.put(state.assetId(), state);
        } catch (IOException e) {
            throw new IllegalStateException("写入本地处理状态失败: " + stateFile, e);
        }
    }

    private void load() {
        if (!Files.exists(stateFile)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(stateFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    ProcessingState state = objectMapper.readValue(line, ProcessingState.class);
                    latestStates.put(state.assetId(), state);
                } catch (Exception ignored) {
                    // 单条状态损坏不能阻止其他图片继续处理。
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取本地处理状态失败: " + stateFile, e);
        }
    }
}
