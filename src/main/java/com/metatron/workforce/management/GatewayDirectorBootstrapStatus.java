package com.metatron.workforce.management;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Internal evidence for the boot-only Gateway Director reconciliation. */
public final class GatewayDirectorBootstrapStatus {
    public enum State { NOT_RUN, DISABLED, READY, DEGRADED }
    public record Snapshot(State state, Instant observedAt, String detail) {}

    private final AtomicReference<Snapshot> snapshot =
            new AtomicReference<>(new Snapshot(State.NOT_RUN, Instant.EPOCH, "not-run"));

    public Snapshot snapshot() { return snapshot.get(); }
    void disabled(Instant at) { record(State.DISABLED, at, "bootstrap-disabled"); }
    void ready(Instant at) { record(State.READY, at, "reconciliation-complete"); }
    void degraded(Instant at, RuntimeException failure) {
        record(State.DEGRADED, at, failure.getClass().getSimpleName() + ": " + Objects.toString(failure.getMessage(), ""));
    }
    private void record(State state, Instant at, String detail) {
        snapshot.set(new Snapshot(state, Objects.requireNonNull(at), detail));
    }
}
