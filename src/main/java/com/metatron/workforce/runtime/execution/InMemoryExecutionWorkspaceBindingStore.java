package com.metatron.workforce.runtime.execution;

import java.util.LinkedHashMap;
import java.util.Map;

public final class InMemoryExecutionWorkspaceBindingStore implements ExecutionWorkspaceBindingStore {
    private Map<String, ExecutionWorkspaceBinding> bindings = new LinkedHashMap<>();
    @Override public synchronized Map<String, ExecutionWorkspaceBinding> load() { return Map.copyOf(bindings); }
    @Override public synchronized void save(Map<String, ExecutionWorkspaceBinding> values) { bindings = new LinkedHashMap<>(values); }
}
