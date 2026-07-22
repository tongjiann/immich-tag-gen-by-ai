package com.xiwang.phototagautogen.domain;

import java.util.List;
import java.util.Objects;

public record TagPath(List<String> segments) {
    public TagPath {
        segments = List.copyOf(segments);
        if (segments.size() < 2 || segments.size() > 4 || segments.stream().anyMatch(s -> s == null || s.isBlank())) {
            throw new IllegalArgumentException("标签路径必须包含 2 至 4 个非空层级");
        }
    }

    public String root() { return segments.getFirst(); }

    public String key() { return String.join("", segments); }

    @Override
    public String toString() { return String.join("/", segments); }
}
