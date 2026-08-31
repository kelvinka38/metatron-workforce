package com.metatron.workforce.execution;

import java.util.Map;

public interface ExecutionAttemptStore {
    Map<String, ExecutionAttempt> load();
    void save(Map<String, ExecutionAttempt> attempts);
}
