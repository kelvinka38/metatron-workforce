package com.metatron.workforce.management;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Durable boundary for Workforce-owned management coordination state. */
public interface ManagementStateStore {
    Snapshot load();
    void save(Snapshot snapshot);

    record Snapshot(
            Map<String, ManagementObjective> objectives,
            Map<String, List<ManagementAutonomyService.ManagementEvent>> events) {
        public Snapshot {
            objectives = Map.copyOf(objectives == null ? Map.of() : new LinkedHashMap<>(objectives));
            Map<String, List<ManagementAutonomyService.ManagementEvent>> copied = new LinkedHashMap<>();
            if (events != null) events.forEach((key, value) -> copied.put(key, List.copyOf(value)));
            events = Map.copyOf(copied);
        }

        public static Snapshot empty() { return new Snapshot(Map.of(), Map.of()); }
    }
}
