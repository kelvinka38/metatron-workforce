package com.metatron.workforce.management;

import java.util.List;

/** Durable boundary for scheduler decisions; separate from Work Graph topology/state. */
public interface AutonomySchedulingStateStore {
    Snapshot load();
    void save(Snapshot snapshot);

    record Snapshot(List<AutonomySchedulingDecision> decisions) {
        public Snapshot {
            decisions = List.copyOf(decisions == null ? List.of() : decisions);
        }
        public static Snapshot empty() { return new Snapshot(List.of()); }
    }
}
