package com.metatron.workforce.operating;

import java.util.List;

/**
 * Durable read-model snapshots of the fully materialized Worker Constitution runtime.
 *
 * Authoritative ownership remains in Workforce Core, Scheduling, Execution, Runtime and the
 * standing WorkerConstitutionService. This store preserves the composed runtime view so a Worker
 * can be inspected across process/runtime replacement without turning the projection into a new
 * source of authority.
 */
public interface WorkerConstitutionRuntimeStateStore {
    Snapshot load();
    void save(Snapshot snapshot);

    record Snapshot(List<WorkerConstitutionRuntimeMaterializer.RuntimeConstitution> constitutions) {
        public Snapshot {
            constitutions = List.copyOf(constitutions == null ? List.of() : constitutions);
        }

        public static Snapshot empty() {
            return new Snapshot(List.of());
        }
    }
}
