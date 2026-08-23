package com.metatron.workforce.runtime;

import java.time.Instant;
import java.util.UUID;

/**
 * Implementation-level Worker Runtime Instance reference.
 *
 * This is not an institutional identity and does not replace Worker or Execution identity.
 */
public final class RuntimeInstance {
    private final String runtimeId;
    private final String workerId;
    private final Instant createdAt;
    private RuntimeState state;

    public RuntimeInstance(String workerId) {
        this.runtimeId = UUID.randomUUID().toString();
        this.workerId = workerId;
        this.createdAt = Instant.now();
        this.state = RuntimeState.CREATED;
    }

    public void transition(RuntimeState nextState) {
        this.state = nextState;
    }

    public String runtimeId() { return runtimeId; }
    public String workerId() { return workerId; }
    public Instant createdAt() { return createdAt; }
    public RuntimeState state() { return state; }
}
