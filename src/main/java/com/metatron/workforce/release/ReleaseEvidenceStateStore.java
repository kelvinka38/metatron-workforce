package com.metatron.workforce.release;

import java.util.Map;

public interface ReleaseEvidenceStateStore {
    Snapshot load();
    void save(Snapshot snapshot);

    record Snapshot(Map<String, ReleaseEvidence> byAssignment) {
        public Snapshot { byAssignment = byAssignment == null ? Map.of() : Map.copyOf(byAssignment); }
        public static Snapshot empty() { return new Snapshot(Map.of()); }
    }
}
