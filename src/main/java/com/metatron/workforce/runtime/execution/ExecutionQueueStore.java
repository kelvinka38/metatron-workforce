package com.metatron.workforce.runtime.execution;

import java.util.Map;

public interface ExecutionQueueStore {
    Snapshot load();
    void save(Snapshot snapshot);
    record Snapshot(Map<String,ExecutionResourceAdmissionRequest> requests, Map<String,ExecutionResourceAdmissionDecision> decisions) {
        public Snapshot {
            requests=requests==null?Map.of():Map.copyOf(requests);
            decisions=decisions==null?Map.of():Map.copyOf(decisions);
        }
    }
}
