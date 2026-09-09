package com.metatron.workforce.runtime;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementation-level registry for Worker Runtime Instances.
 *
 * The registry owns only runtime-instance references. Durable continuity is
 * delegated to RuntimePersistenceStore and therefore survives JVM replacement
 * when a durable store is configured.
 */
public final class RuntimeRegistry {
    private final Map<String, RuntimeInstance> runtimes = new ConcurrentHashMap<>();
    private final RuntimePersistenceStore persistence;

    public RuntimeRegistry() {
        this(new InMemoryRuntimePersistenceStore());
    }

    public RuntimeRegistry(RuntimePersistenceStore persistence) {
        this.persistence = persistence;
    }

    public RuntimeInstance register(RuntimeInstance runtime) {
        runtimes.put(runtime.runtimeId(), runtime);
        persist(runtime);
        return runtime;
    }

    public RuntimeInstance get(String runtimeId) {
        RuntimeInstance runtime = runtimes.get(runtimeId);
        if (runtime != null) {
            return runtime;
        }
        return persistence.find(runtimeId)
                .map(RuntimeInstance::restore)
                .map(restored -> {
                    runtimes.put(restored.runtimeId(), restored);
                    return restored;
                })
                .orElse(null);
    }

    public List<RuntimeInstance> all() {
        persistence.list().forEach(record -> runtimes.computeIfAbsent(
                record.runtimeId(), ignored -> RuntimeInstance.restore(record)));
        return runtimes.values().stream()
                .sorted(Comparator.comparing(RuntimeInstance::runtimeId))
                .toList();
    }

    public Optional<RuntimeInstance> runningForWorker(String workerId) {
        if (workerId == null || workerId.isBlank()) return Optional.empty();
        return all().stream()
                .filter(runtime -> workerId.equals(runtime.workerId()))
                .filter(runtime -> runtime.state() == RuntimeState.RUNNING)
                .sorted(Comparator.comparing(RuntimeInstance::createdAt).reversed()
                        .thenComparing(RuntimeInstance::runtimeId))
                .findFirst();
    }

    public boolean remove(String runtimeId) {
        boolean removed = runtimes.remove(runtimeId) != null;
        persistence.delete(runtimeId);
        return removed;
    }

    public int size() {
        return runtimes.size();
    }

    public void persist(RuntimeInstance runtime) {
        persistence.save(new RuntimePersistenceRecord(
                runtime.runtimeId(),
                runtime.workerId(),
                runtime.state().name(),
                Instant.now()));
    }
}
