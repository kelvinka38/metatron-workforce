package com.metatron.workforce.deliberation;

import java.time.Instant;
import java.util.List;

/** Durable role-independent work-interaction state owned by Metatron, not by an LLM session. */
public record WorkerDeliberationState(
        String workerId,
        String objectiveSummary,
        WorkerWorkStage stage,
        WorkerNextMove nextMove,
        String intent,
        String contextSufficiency,
        List<String> assumptions,
        List<String> openQuestions,
        List<String> decisions,
        int revisionCount,
        int clarificationCount,
        Instant updatedAt) {

    public static WorkerDeliberationState initial(String workerId) {
        return new WorkerDeliberationState(
                workerId, "", WorkerWorkStage.IDLE, WorkerNextMove.CONVERSE,
                "CONVERSATION", "UNKNOWN", List.of(), List.of(), List.of(), 0, 0, Instant.now());
    }
}
