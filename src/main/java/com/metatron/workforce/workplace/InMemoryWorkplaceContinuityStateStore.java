package com.metatron.workforce.workplace;

public final class InMemoryWorkplaceContinuityStateStore implements WorkplaceContinuityStateStore {
    private Snapshot snapshot = Snapshot.empty();
    @Override public synchronized Snapshot load() { return snapshot; }
    @Override public synchronized void save(Snapshot snapshot) { this.snapshot = snapshot; }
}
