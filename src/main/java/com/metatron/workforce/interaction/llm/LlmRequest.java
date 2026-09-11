package com.metatron.workforce.interaction.llm;

import java.util.Objects;

/**
 * Provider-bound frontier request.
 *
 * logicalRequestRef/caseRef/purpose/reasonCode are observational metadata only. They must never be
 * interpreted as Worker identity, authority, authorization or provider-owned memory.
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
        FrontierCallBudget callBudget) {

    /** Backward-compatible constructor for callers that do not yet provide cognitive trace metadata. */
    public LlmRequest(LlmProvider provider, String model, String systemContext, String userInput) {
        this(provider, model, systemContext, userInput, "", "", "unspecified", "",
                FrontierCallBudget.legacyUnbounded());
    }

    /** Backward-compatible trace constructor predating explicit escalation reason codes. */
    public LlmRequest(LlmProvider provider, String model, String systemContext, String userInput,
                      String logicalRequestRef, String caseRef, String purpose) {
        this(provider, model, systemContext, userInput, logicalRequestRef, caseRef, purpose, "",
                FrontierCallBudget.legacyUnbounded());
    }

    /** Backward-compatible reason-coded constructor predating hard provider budgets. */
    public LlmRequest(LlmProvider provider, String model, String systemContext, String userInput,
                      String logicalRequestRef, String caseRef, String purpose, String reasonCode) {
        this(provider, model, systemContext, userInput, logicalRequestRef, caseRef, purpose, reasonCode,
                FrontierCallBudget.legacyUnbounded());
    }

    public LlmRequest {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(systemContext, "systemContext");
        Objects.requireNonNull(userInput, "userInput");
        logicalRequestRef = logicalRequestRef == null ? "" : logicalRequestRef.trim();
        caseRef = caseRef == null ? "" : caseRef.trim();
        purpose = purpose == null || purpose.isBlank() ? "unspecified" : purpose.trim();
        reasonCode = reasonCode == null ? "" : reasonCode.trim();
        callBudget = callBudget == null ? FrontierCallBudget.legacyUnbounded() : callBudget;
        if (model.isBlank() || userInput.isBlank()) {
            throw new IllegalArgumentException("model and userInput must not be blank");
        }
    }
}
