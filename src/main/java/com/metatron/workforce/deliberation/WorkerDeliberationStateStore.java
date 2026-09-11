package com.metatron.workforce.deliberation;

import java.util.Map;

/** Durable persistence boundary for universal Worker deliberation state. */
public interface WorkerDeliberationStateStore {
    Map<String, WorkerDeliberationState> load();
    void save(Map<String, WorkerDeliberationState> states);
}
