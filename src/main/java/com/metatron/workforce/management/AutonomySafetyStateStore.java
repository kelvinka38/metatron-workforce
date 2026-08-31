package com.metatron.workforce.management;

import java.util.LinkedHashMap;
import java.util.Map;

/** Durable storage contract for operational autonomy safety state. */
public interface AutonomySafetyStateStore {
    Snapshot load();
    void save(Snapshot snapshot);

    record Snapshot(Map<String, AutonomySafetyState> objectives) {
        public Snapshot {
            objectives = Map.copyOf(objectives == null ? Map.of() : objectives);
        }
        public static Snapshot empty() { return new Snapshot(Map.of()); }
        public Map<String, AutonomySafetyState> mutableObjectives() { return new LinkedHashMap<>(objectives); }
    }
}
