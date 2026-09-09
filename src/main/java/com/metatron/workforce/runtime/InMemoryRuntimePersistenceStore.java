package com.metatron.workforce.runtime;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Test/default technical store. Not a production durability mechanism. */
public final class InMemoryRuntimePersistenceStore implements RuntimePersistenceStore {
    private final Map<String, RuntimePersistenceRecord> records = new ConcurrentHashMap<>();

    @Override
    public void save(RuntimePersistenceRecord record) {
        records.put(record.runtimeId(), record);
    }

    @Override
    public Optional<RuntimePersistenceRecord> find(String runtimeId) {
        return Optional.ofNullable(records.get(runtimeId));
    }

    @Override
    public List<RuntimePersistenceRecord> list() {
        return records.values().stream()
                .sorted(Comparator.comparing(RuntimePersistenceRecord::runtimeId))
                .toList();
    }

    @Override
    public void delete(String runtimeId) {
        records.remove(runtimeId);
    }
}
