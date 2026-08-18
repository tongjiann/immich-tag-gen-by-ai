package com.xiwang.phototagautogen.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiwang.phototagautogen.config.ProcessingProperties;
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
import java.util.Map;
import java.util.UUID;

@Component
public class JsonlStateStore {
    private static final String STATUS_SUCCESS = "SUCCESS";

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

    public synchronized void appendSuccess(UUID assetId, String fileModifiedAt, String model,
                                            String promptVersion, int taxonomyVersion) {
        append(new ProcessingState(assetId, fileModifiedAt, model, promptVersion, taxonomyVersion,
                STATUS_SUCCESS, Instant.now(), null));
    }

    public synchronized void appendFailure(UUID assetId, String fileModifiedAt, String model,
                                            String promptVersion, int taxonomyVersion, String errorCode) {
        append(new ProcessingState(assetId, fileModifiedAt, model, promptVersion, taxonomyVersion,
                "FAILED", Instant.now(), errorCode));
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
