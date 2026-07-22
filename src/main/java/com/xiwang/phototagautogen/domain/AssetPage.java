package com.xiwang.phototagautogen.domain;

import java.util.List;

public record AssetPage(List<ImmichAsset> assets, boolean hasMore) {
    public AssetPage {
        assets = List.copyOf(assets);
    }
}
