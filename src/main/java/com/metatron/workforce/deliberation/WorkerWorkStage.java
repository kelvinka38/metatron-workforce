package com.metatron.workforce.deliberation;

/** Universal, role-independent stage of an institutional Worker's current work interaction. */
public enum WorkerWorkStage {
    IDLE,
    UNDERSTANDING,
    CONTEXTUALIZING,
    DISCUSSING,
    CLARIFYING,
    PROPOSING,
    PLANNING,
    EXECUTING,
    INSPECTING,
    CRITIQUING,
    REVISING,
    VERIFYING,
    READY_TO_DELIVER,
    DELIVERED,
    WAITING_FOR_HUMAN,
    BLOCKED,
    ESCALATED,
    PAUSED,
    CANCELLED
}
