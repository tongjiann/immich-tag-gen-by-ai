package com.xiwang.phototagautogen.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AssetDetail(UUID id, String type, String description, Instant fileModifiedAt, List<ImmichTag> tags) {
    public AssetDetail {
        tags = List.copyOf(tags);
    }

    public boolean isImage() {
        return "IMAGE".equalsIgnoreCase(type);
    }
}
