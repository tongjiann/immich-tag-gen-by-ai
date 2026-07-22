package com.xiwang.phototagautogen.domain;

import java.util.concurrent.atomic.AtomicInteger;

public final class ProcessingSummary {
    private final AtomicInteger scanned = new AtomicInteger();
    private final AtomicInteger skipped = new AtomicInteger();
    private final AtomicInteger analyzed = new AtomicInteger();
    private final AtomicInteger descriptionsUpdated = new AtomicInteger();
    private final AtomicInteger tagsAdded = new AtomicInteger();
    private final AtomicInteger failures = new AtomicInteger();

    public void scanned() { scanned.incrementAndGet(); }
    public void skipped() { skipped.incrementAndGet(); }
    public void analyzed() { analyzed.incrementAndGet(); }
    public void descriptionUpdated() { descriptionsUpdated.incrementAndGet(); }
    public void tagsAdded(int count) { tagsAdded.addAndGet(count); }
    public void failed() { failures.incrementAndGet(); }
    public int scannedCount() { return scanned.get(); }
    public int skippedCount() { return skipped.get(); }
    public int analyzedCount() { return analyzed.get(); }
    public int descriptionsUpdatedCount() { return descriptionsUpdated.get(); }
    public int tagsAddedCount() { return tagsAdded.get(); }
    public int failures() { return failures.get(); }

    @Override
    public String toString() {
        return "扫描=" + scanned.get() + ", 跳过=" + skipped.get() + ", 推理=" + analyzed.get()
                + ", 描述更新=" + descriptionsUpdated.get() + ", 新增标签=" + tagsAdded.get()
                + ", 失败=" + failures.get();
    }
}
