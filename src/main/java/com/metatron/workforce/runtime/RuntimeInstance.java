package com.metatron.workforce.runtime;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class RuntimeInstance {

    private final String runtimeId;
    private final String workerId;
    private final Instant createdAt;
    private RuntimeState state;

    public RuntimeInstance(String workerId) {
        this(UUID.randomUUID().toString(), workerId, RuntimeState.CREATED, Instant.now());
    }

    public RuntimeInstance(
            String runtimeId,
            String workerId,
            RuntimeState state) {
        this(runtimeId, workerId, state, Instant.now());
    }

    private RuntimeInstance(
            String runtimeId,
            String workerId,
            RuntimeState state,
            Instant createdAt) {
        this.runtimeId = require(runtimeId);
        this.workerId = require(workerId);
        this.state = Objects.requireNonNull(state);
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    static RuntimeInstance restore(RuntimePersistenceRecord record) {
        return new RuntimeInstance(
                record.runtimeId(),
                record.workerId(),
                RuntimeState.valueOf(record.state()),
                record.updatedAt());
    }

    public void transition(RuntimeState nextState) {
        Objects.requireNonNull(nextState);

        if (state == RuntimeState.TERMINATED) {
            throw new IllegalStateException("terminated runtime cannot transition");
        }

        this.state = nextState;
    }

    private static String require(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
        return value;
    }

    public String runtimeId() {
        return runtimeId;
    }

    public String workerId() {
        return workerId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public RuntimeState state() {
        return state;
    }
}
