package com.metatron.workforce.runtime.execution;

import java.util.Map;

public interface ResourceStateStore {
    Snapshot load();
    void save(Snapshot snapshot);

    record Snapshot(Map<String,ResourceLease> leases, Map<String,ResourceFencingState> fencing) {
        public Snapshot {
            leases = leases == null ? Map.of() : Map.copyOf(leases);
            fencing = fencing == null ? Map.of() : Map.copyOf(fencing);
        }
    }
}
