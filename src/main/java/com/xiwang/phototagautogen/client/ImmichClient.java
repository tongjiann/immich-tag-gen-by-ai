package com.xiwang.phototagautogen.client;

import com.xiwang.phototagautogen.domain.AssetDetail;
import com.xiwang.phototagautogen.domain.AssetPage;
import com.xiwang.phototagautogen.domain.TagIndex;
import com.xiwang.phototagautogen.domain.TagPath;

import java.util.Collection;
import java.util.UUID;

public interface ImmichClient {
    void validateConnection();
    AssetPage listImages(int page, int size);
    AssetDetail getAsset(UUID assetId);
    byte[] downloadPreview(UUID assetId);
    void updateDescription(UUID assetId, String description);
    TagIndex listTags();
    UUID ensureTagPath(TagPath path, TagIndex tagIndex);
    void attachTags(UUID assetId, Collection<UUID> tagIds);
}
