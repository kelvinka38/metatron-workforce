package com.metatron.workforce.runtime;

import java.util.Optional;

/**
 * Durable boundary for runtime-instance continuity.
 *
 * The store owns technical persistence only; it does not own Worker identity,
 * authorization, or execution semantics.
 */
public interface RuntimePersistenceStore {

    void save(RuntimePersistenceRecord record);

    Optional<RuntimePersistenceRecord> find(String runtimeId);

    void delete(String runtimeId);
}
