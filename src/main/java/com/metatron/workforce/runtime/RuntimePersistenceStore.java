package com.metatron.workforce.runtime;

import java.util.List;
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

    List<RuntimePersistenceRecord> list();

    void delete(String runtimeId);
}
