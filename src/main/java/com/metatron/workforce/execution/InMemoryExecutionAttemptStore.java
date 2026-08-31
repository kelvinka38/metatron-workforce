package com.metatron.workforce.execution;

import java.util.LinkedHashMap;
import java.util.Map;

public final class InMemoryExecutionAttemptStore implements ExecutionAttemptStore {
    private Map<String,ExecutionAttempt> state = Map.of();
    @Override public synchronized Map<String,ExecutionAttempt> load(){ return Map.copyOf(state); }
    @Override public synchronized void save(Map<String,ExecutionAttempt> attempts){ state = Map.copyOf(new LinkedHashMap<>(attempts)); }
}
