package com.xiwang.phototagautogen.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ProcessingState(UUID assetId, String fileModifiedAt, String model, String promptVersion,
                              int taxonomyVersion, String status, Instant processedAt, String errorCode,
                              boolean assetDetailRead, boolean albumsRead, String assetType, String description,
                              List<ImmichTag> tags, List<ImmichAlbum> albums) {

    public ProcessingState {
        tags = tags == null ? List.of() : List.copyOf(tags);
        albums = albums == null ? List.of() : List.copyOf(albums);
    }

    public boolean fileMatches(String currentFileModifiedAt) {
        return currentFileModifiedAt != null && !currentFileModifiedAt.isBlank()
                && Objects.equals(fileModifiedAt, currentFileModifiedAt);
    }

    public boolean readMatches(String currentFileModifiedAt) {
        return assetDetailRead && fileMatches(currentFileModifiedAt);
    }

    public AssetDetail cachedAssetDetail(UUID fallbackAssetId, String fallbackType, Instant fallbackModifiedAt) {
        UUID resolvedAssetId = assetId == null ? fallbackAssetId : assetId;
        String resolvedType = assetType == null || assetType.isBlank() ? fallbackType : assetType;
        Instant resolvedModifiedAt = parseInstant(fileModifiedAt, fallbackModifiedAt);
        return new AssetDetail(resolvedAssetId, resolvedType, description, resolvedModifiedAt, tags);
    }

    private Instant parseInstant(String value, Instant fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}
