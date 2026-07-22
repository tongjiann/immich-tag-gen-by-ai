package com.xiwang.phototagautogen.client;

import com.xiwang.phototagautogen.domain.ImageAnalysis;
import com.xiwang.phototagautogen.domain.Taxonomy;

public interface VisionModelClient {
    void validateConnection();
    ImageAnalysis analyze(byte[] image, Taxonomy taxonomy);
    String modelName();

    default boolean supportsResourceRelease() {
        return false;
    }

    default void releaseResources() {
    }
}
