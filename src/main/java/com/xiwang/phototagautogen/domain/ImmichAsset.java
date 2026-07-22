package com.xiwang.phototagautogen.domain;

import java.time.Instant;
import java.util.UUID;

public record ImmichAsset(UUID id, String type, boolean trashed, boolean archived, Instant fileModifiedAt) {
    public boolean isImage() {
        return "IMAGE".equalsIgnoreCase(type);
    }
}
