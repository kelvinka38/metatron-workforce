package com.metatron.workforce.management;

/** In-memory scheduling store for deterministic tests and compatibility composition. */
public final class InMemoryAutonomySchedulingStateStore implements AutonomySchedulingStateStore {
    private Snapshot snapshot = Snapshot.empty();
    @Override public synchronized Snapshot load() { return snapshot; }
    @Override public synchronized void save(Snapshot snapshot) { this.snapshot = snapshot; }
}
