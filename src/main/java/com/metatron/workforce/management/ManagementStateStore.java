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
            Map<String, List<ManagementAutonomyService.ManagementEvent>> events,
            Map<String, AutonomousObjectiveWork> objectiveWork,
            Map<String, ManagementLease> leases,
            List<ManagementOutboxMessage> outbox) {
        public Snapshot {
            objectives = Map.copyOf(objectives == null ? Map.of() : new LinkedHashMap<>(objectives));
            Map<String, List<ManagementAutonomyService.ManagementEvent>> copied = new LinkedHashMap<>();
            if (events != null) events.forEach((key, value) -> copied.put(key, List.copyOf(value)));
            events = Map.copyOf(copied);
            objectiveWork = Map.copyOf(objectiveWork == null ? Map.of() : new LinkedHashMap<>(objectiveWork));
            leases = Map.copyOf(leases == null ? Map.of() : new LinkedHashMap<>(leases));
            outbox = List.copyOf(outbox == null ? List.of() : outbox);
        }

        /** Backward-compatible constructor for callers predating autonomy-closure state. */
        public Snapshot(Map<String, ManagementObjective> objectives,
                        Map<String, List<ManagementAutonomyService.ManagementEvent>> events) {
            this(objectives, events, Map.of(), Map.of(), List.of());
        }

        public static Snapshot empty() {
            return new Snapshot(Map.of(), Map.of(), Map.of(), Map.of(), List.of());
        }
    }
}
