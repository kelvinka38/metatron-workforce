package com.metatron.workforce.observation;

public final class InMemoryObservationStateStore implements ObservationStateStore {
    private Snapshot snapshot = Snapshot.empty();
    @Override public synchronized Snapshot load() { return snapshot; }
    @Override public synchronized void save(Snapshot snapshot) { this.snapshot = snapshot; }
}
