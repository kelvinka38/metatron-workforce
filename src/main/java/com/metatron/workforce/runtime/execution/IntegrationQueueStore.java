package com.metatron.workforce.runtime.execution;

import java.util.LinkedHashMap;
import java.util.Map;

public interface IntegrationQueueStore {
    Map<String, IntegrationQueueEntry> load();
    void save(Map<String, IntegrationQueueEntry> entries);

    static Map<String, IntegrationQueueEntry> immutable(Map<String, IntegrationQueueEntry> entries) {
        return Map.copyOf(new LinkedHashMap<>(entries == null ? Map.of() : entries));
    }
}
