package com.metatron.workforce.management;

/** Test/local implementation of the autonomy safety store. */
public final class InMemoryAutonomySafetyStateStore implements AutonomySafetyStateStore {
    private Snapshot snapshot = Snapshot.empty();
    @Override public synchronized Snapshot load() { return snapshot; }
    @Override public synchronized void save(Snapshot snapshot) { this.snapshot = snapshot; }
}
