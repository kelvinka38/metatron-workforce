package com.metatron.workforce.runtime.execution;

public final class InMemoryExecutionQueueStore implements ExecutionQueueStore {
    private Snapshot snapshot=new Snapshot(java.util.Map.of(),java.util.Map.of());
    @Override public synchronized Snapshot load(){return snapshot;}
    @Override public synchronized void save(Snapshot value){snapshot=value;}
}
