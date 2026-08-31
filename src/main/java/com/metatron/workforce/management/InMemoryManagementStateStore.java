package com.metatron.workforce.management;

import java.util.LinkedHashMap;

/** Process-local compatibility store. Production callers should inject a durable store. */
public final class InMemoryManagementStateStore implements ManagementStateStore {
    private Snapshot snapshot = Snapshot.empty();

    @Override
    public synchronized Snapshot load() {
        return new Snapshot(new LinkedHashMap<>(snapshot.objectives()),
                new LinkedHashMap<>(snapshot.events()),
                new LinkedHashMap<>(snapshot.objectiveWork()),
                new LinkedHashMap<>(snapshot.leases()),
                snapshot.outbox());
    }

    @Override
    public synchronized void save(Snapshot snapshot) {
        this.snapshot = new Snapshot(snapshot.objectives(), snapshot.events(),
                snapshot.objectiveWork(), snapshot.leases(), snapshot.outbox());
    }
}
