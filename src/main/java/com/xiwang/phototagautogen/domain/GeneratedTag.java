package com.xiwang.phototagautogen.domain;

import java.math.BigDecimal;
import java.util.List;

public record GeneratedTag(List<String> path, BigDecimal confidence) {
    public GeneratedTag {
        path = List.copyOf(path);
    }

    public TagPath tagPath() { return new TagPath(path); }
}
