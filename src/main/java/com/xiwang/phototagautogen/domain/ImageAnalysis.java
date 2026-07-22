package com.xiwang.phototagautogen.domain;

import java.util.List;

public record ImageAnalysis(String description, List<GeneratedTag> tags, boolean portraitSubject) {
    public ImageAnalysis {
        tags = List.copyOf(tags);
    }
}
