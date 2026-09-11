package com.metatron.workforce.runtime.execution;

import java.util.Map;

public interface ExecutionWorkspaceBindingStore {
    Map<String, ExecutionWorkspaceBinding> load();
    void save(Map<String, ExecutionWorkspaceBinding> bindings);
}
