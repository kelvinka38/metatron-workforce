package com.metatron.workforce.runtime;

import java.time.Instant;

/**
 * Persistence boundary model for runtime-instance recovery metadata.
 *
 * This is not a persistence implementation. It defines the minimal
 * implementation record required to preserve runtime reference continuity.
 */
public record RuntimePersistenceRecord(
        String runtimeId,
        String workerId,
        String state,
        Instant updatedAt
) {}
