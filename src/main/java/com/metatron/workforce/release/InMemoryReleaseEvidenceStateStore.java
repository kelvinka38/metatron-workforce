package com.metatron.workforce.release;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryReleaseEvidenceStateStore implements ReleaseEvidenceStateStore {
    private final Map<String, ReleaseEvidence> data = new ConcurrentHashMap<>();

    @Override public synchronized Snapshot load() { return new Snapshot(Map.copyOf(data)); }

    @Override public synchronized void save(Snapshot snapshot) {
        data.clear();
        data.putAll(snapshot.byAssignment());
    }
}
