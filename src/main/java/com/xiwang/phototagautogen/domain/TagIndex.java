package com.xiwang.phototagautogen.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class TagIndex {
    private final Map<String, ImmichTag> byPath = new HashMap<>();
    private final Map<UUID, String> pathById = new HashMap<>();

    public synchronized void addAll(Collection<ImmichTag> tags) {
        for (ImmichTag tag : tags) {
            String path = buildPath(tag.id(), tags);
            if (path != null) {
                byPath.put(path, tag);
                pathById.put(tag.id(), path);
            }
        }
    }

    public synchronized void add(ImmichTag tag, String path) {
        byPath.put(path, tag);
        pathById.put(tag.id(), path);
    }

    public synchronized ImmichTag find(String path) { return byPath.get(path); }

    public synchronized String pathOf(UUID id) { return pathById.get(id); }

    public synchronized List<ImmichTag> all() { return new ArrayList<>(byPath.values()); }

    public synchronized List<String> getFullPathTags() {
        return byPath.keySet().stream().toList();
    }

    private String buildPath(UUID id, Collection<ImmichTag> allTags) {
        Map<UUID, ImmichTag> byId = new HashMap<>();
        allTags.forEach(tag -> byId.put(tag.id(), tag));
        List<String> segments = new ArrayList<>();
        ImmichTag current = byId.get(id);
        int depth = 0;
        while (current != null && depth++ < 4) {
            segments.addFirst(current.name());
            current = current.parentId() == null ? null : byId.get(current.parentId());
        }
        return segments.isEmpty() ? null : String.join("/", segments);
    }
}
