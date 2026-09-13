package com.metatron.workforce.interaction.intelligence;

/** Capacity sub-lifecycle for one logical Metatron-owned cognition request. */
public enum CognitionRequestState {
    ADMITTED,
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED_RETRYABLE,
    FAILED_TERMINAL,
    RECONCILIATION_REQUIRED
}
