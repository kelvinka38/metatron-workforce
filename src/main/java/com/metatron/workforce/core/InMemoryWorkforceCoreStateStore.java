package com.metatron.workforce.core;

public final class InMemoryWorkforceCoreStateStore implements WorkforceCoreStateStore {
    private Snapshot snapshot = Snapshot.empty();
    @Override public synchronized Snapshot load() { return snapshot; }
    @Override public synchronized void save(Snapshot snapshot) { this.snapshot = snapshot; }
}
