package com.metatron.workforce.interaction.llm;

import java.util.Objects;

/**
 * Provider-bound frontier request.
 *
 * logicalRequestRef/caseRef/purpose/reasonCode and origin lineage are observational metadata only.
 * They must never be interpreted as authority, authorization, provider-owned memory or Worker identity creation.
 */
public record LlmRequest(
        LlmProvider provider,
        String model,
        String systemContext,
        String userInput,
        String logicalRequestRef,
        String caseRef,
        String purpose,
        String reasonCode,
        FrontierCallBudget callBudget,
        String originType,
        String actorId,
        String workerId,
        String objectiveId,
        String assignmentId,
        String stepId,
        String executionAttemptId) {

    /** Backward-compatible constructor for callers that do not yet provide cognitive trace metadata. */
    public LlmRequest(LlmProvider provider, String model, String systemContext, String userInput) {
        this(provider, model, systemContext, userInput, "", "", "unspecified", "",
                FrontierCallBudget.legacyUnbounded(), "UNSPECIFIED", "", "", "", "", "", "");
    }

    /** Backward-compatible trace constructor predating explicit escalation reason codes. */
    public LlmRequest(LlmProvider provider, String model, String systemContext, String userInput,
                      String logicalRequestRef, String caseRef, String purpose) {
        this(provider, model, systemContext, userInput, logicalRequestRef, caseRef, purpose, "",
                FrontierCallBudget.legacyUnbounded(), "UNSPECIFIED", "", "", "", "", "", "");
    }

    /** Backward-compatible reason-coded constructor predating hard provider budgets. */
    public LlmRequest(LlmProvider provider, String model, String systemContext, String userInput,
                      String logicalRequestRef, String caseRef, String purpose, String reasonCode) {
        this(provider, model, systemContext, userInput, logicalRequestRef, caseRef, purpose, reasonCode,
                FrontierCallBudget.legacyUnbounded(), "UNSPECIFIED", "", "", "", "", "", "");
    }

    /** Backward-compatible budget constructor predating typed frontier origin propagation. */
    public LlmRequest(LlmProvider provider, String model, String systemContext, String userInput,
                      String logicalRequestRef, String caseRef, String purpose, String reasonCode,
                      FrontierCallBudget callBudget) {
        this(provider, model, systemContext, userInput, logicalRequestRef, caseRef, purpose, reasonCode,
                callBudget, "UNSPECIFIED", "", "", "", "", "", "");
    }

    public LlmRequest {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(systemContext, "systemContext");
        Objects.requireNonNull(userInput, "userInput");
        logicalRequestRef = clean(logicalRequestRef);
        caseRef = clean(caseRef);
        purpose = purpose == null || purpose.isBlank() ? "unspecified" : purpose.trim();
        reasonCode = clean(reasonCode);
        callBudget = callBudget == null ? FrontierCallBudget.legacyUnbounded() : callBudget;
        originType = originType == null || originType.isBlank() ? "UNSPECIFIED" : originType.trim().toUpperCase();
        actorId = clean(actorId);
        workerId = clean(workerId);
        objectiveId = clean(objectiveId);
        assignmentId = clean(assignmentId);
        stepId = clean(stepId);
        executionAttemptId = clean(executionAttemptId);
        if (model.isBlank() || userInput.isBlank()) {
            throw new IllegalArgumentException("model and userInput must not be blank");
        }
        if ("WORKER".equals(originType) && workerId.isBlank()) {
            throw new IllegalArgumentException("WORKER frontier request requires workerId");
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
