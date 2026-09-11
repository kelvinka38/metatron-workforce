package com.metatron.workforce.runtime.execution;

import java.util.LinkedHashMap;
import java.util.Map;

public final class InMemoryIntegrationQueueStore implements IntegrationQueueStore {
    private Map<String, IntegrationQueueEntry> state = Map.of();
    @Override public synchronized Map<String, IntegrationQueueEntry> load() { return Map.copyOf(state); }
    @Override public synchronized void save(Map<String, IntegrationQueueEntry> entries) {
        state = Map.copyOf(new LinkedHashMap<>(entries));
    }
}
