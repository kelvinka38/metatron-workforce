package com.metatron.workforce.runtime.execution;

public final class InMemoryResourceStateStore implements ResourceStateStore {
    private Snapshot snapshot = new Snapshot(java.util.Map.of(), java.util.Map.of());
    @Override public synchronized Snapshot load(){ return snapshot; }
    @Override public synchronized void save(Snapshot value){ snapshot = value; }
}
