package com.metatron.workforce.interaction.intelligence;

/** Runtime lifecycle state for an Intelligence Case; not a canonical institutional lifecycle. */
public enum IntelligenceCaseStatus {
    OPEN,
    UNDERSTANDING,
    INFORMATION_ASSESSMENT,
    ACQUISITION,
    REASONING,
    RESULT_READY,
    WAITING_ON_EXTERNAL_STATE,
    REASSESSMENT,
    RESOLVED
}
