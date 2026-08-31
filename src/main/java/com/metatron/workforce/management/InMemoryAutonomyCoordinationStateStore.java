package com.metatron.workforce.management;

/** Test/local coordination state. Production uses the file-backed durable implementation. */
public final class InMemoryAutonomyCoordinationStateStore implements AutonomyCoordinationStateStore {
    private Snapshot snapshot = Snapshot.empty();
    @Override public synchronized Snapshot load() { return snapshot; }
    @Override public synchronized void save(Snapshot snapshot) { this.snapshot = snapshot; }
}
