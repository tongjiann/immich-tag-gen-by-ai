package com.xiwang.phototagautogen.domain;

import java.math.BigDecimal;
import java.util.List;

public record GeneratedTag(List<String> path, String parentTag, BigDecimal confidence) {
    public GeneratedTag(List<String> path, BigDecimal confidence) {
        this(path, parentTagOf(path), confidence);
    }

    public GeneratedTag {
        path = List.copyOf(path);
        parentTag = parentTag == null ? null : parentTag.trim();
    }

    public TagPath tagPath() { return new TagPath(path); }

    public boolean hasMatchingParentTag() {
        return parentTag != null && parentTag.equals(parentTagOf(path));
    }

    private static String parentTagOf(List<String> path) {
        if (path == null || path.size() < 2) {
            return null;
        }
        return String.join("/", path.subList(0, path.size() - 1));
    }
}
