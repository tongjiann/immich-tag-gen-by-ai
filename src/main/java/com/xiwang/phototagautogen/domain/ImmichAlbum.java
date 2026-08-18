package com.xiwang.phototagautogen.domain;

import java.util.List;
import java.util.UUID;

public record ImmichAlbum(UUID id, String albumName, List<UUID> assetIds) {
    public ImmichAlbum {
        assetIds = List.copyOf(assetIds);
    }
}
