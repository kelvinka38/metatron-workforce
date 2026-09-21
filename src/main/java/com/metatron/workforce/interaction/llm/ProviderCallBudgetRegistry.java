package com.metatron.workforce.interaction.llm;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hard in-process admission control for frontier calls keyed by logical request.
 * Failed calls still consume budget. This registry controls capacity only; it grants no authority.
 */
public final class ProviderCallBudgetRegistry {
    public static final String PROVIDER_FAILURE = "PROVIDER_FAILURE";
    public static final String EXPLICIT_HUMAN_REQUEST = "EXPLICIT_HUMAN_REQUEST";

    private final ConcurrentHashMap<String, Counters> counters = new ConcurrentHashMap<>();

    public Permit authorize(LlmRequest request) {
        Objects.requireNonNull(request, "request");
        FrontierCallBudget budget = request.callBudget();
        if (!budget.enforced()) return Permit.legacy();

        String logicalRef = request.logicalRequestRef() == null ? "" : request.logicalRequestRef().trim();
        if (logicalRef.isBlank()) {
            throw new IllegalStateException("frontier_budget_requires_logical_request_ref");
        }

        Counters state = counters.computeIfAbsent(logicalRef, ignored -> new Counters());
        synchronized (state) {
            String reason = request.reasonCode() == null ? "" : request.reasonCode().trim();
            CallClass callClass;
            if (reason.isBlank()) {
                callClass = CallClass.INITIAL;
                if (state.initial >= budget.maxInitialCalls()) {
                    throw denied(logicalRef, "initial", budget, state);
                }
                state.initial++;
            } else if (PROVIDER_FAILURE.equals(reason)) {
                callClass = CallClass.FALLBACK;
                if (state.fallback >= budget.maxFallbackCalls()) {
                    throw denied(logicalRef, "fallback", budget, state);
                }
                state.fallback++;
            } else {
                callClass = CallClass.ESCALATION;
                if (!budget.allowsEscalation(reason)) {
                    throw new IllegalStateException("frontier_escalation_reason_not_allowed:" + reason);
                }
                if (EXPLICIT_HUMAN_REQUEST.equals(reason) && !budget.multiModelAllowed()) {
                    throw new IllegalStateException("frontier_multi_model_not_allowed");
                }
                if (state.escalation >= budget.maxEscalationCalls()) {
                    throw denied(logicalRef, "escalation", budget, state);
                }
                state.escalation++;
            }

            if (state.total() > budget.maxTotalCalls()) {
                throw denied(logicalRef, "total", budget, state);
            }
            return new Permit(logicalRef, callClass, reason, state.initial, state.fallback, state.escalation);
        }
    }

    public Snapshot snapshot(String logicalRequestRef) {
        String ref = logicalRequestRef == null ? "" : logicalRequestRef.trim();
        Counters state = counters.get(ref);
        if (state == null) return new Snapshot(ref, 0, 0, 0);
        synchronized (state) {
            return new Snapshot(ref, state.initial, state.fallback, state.escalation);
        }
    }

    public Map<String, Snapshot> snapshots() {
        return counters.keySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                key -> key,
                this::snapshot));
    }

    public void clear() {
        counters.clear();
    }

    /**
     * Releases the bounded-call bookkeeping for exactly one logical request once its cognitive
     * pass has finished (success or failure). Without this, a durably-retried interaction that
     * reuses the same logical request reference (e.g. the same Telegram update retried after a
     * failure) would inherit the prior pass's already-exhausted counters and fail every provider
     * immediately -- a retry in name only. Each retried pass gets its own fresh, still-bounded
     * budget instead of accumulating across passes forever.
     */
    public void release(String logicalRequestRef) {
        String ref = logicalRequestRef == null ? "" : logicalRequestRef.trim();
        if (!ref.isBlank()) counters.remove(ref);
    }

    private static IllegalStateException denied(String logicalRef, String category,
                                                FrontierCallBudget budget, Counters state) {
        return new IllegalStateException("frontier_call_budget_exhausted:logical_request=" + logicalRef
                + ":category=" + category
                + ":counts=" + state.initial + "/" + state.fallback + "/" + state.escalation
                + ":budget=" + budget.maxInitialCalls() + "/" + budget.maxFallbackCalls()
                + "/" + budget.maxEscalationCalls());
    }

    private enum CallClass { INITIAL, FALLBACK, ESCALATION, LEGACY }

    private static final class Counters {
        int initial;
        int fallback;
        int escalation;
        int total() { return initial + fallback + escalation; }
    }

    public record Permit(String logicalRequestRef, CallClass callClass, String reasonCode,
                         int initialCalls, int fallbackCalls, int escalationCalls) {
        private static Permit legacy() {
            return new Permit("", CallClass.LEGACY, "", 0, 0, 0);
        }
    }

    public record Snapshot(String logicalRequestRef, int initialCalls, int fallbackCalls, int escalationCalls) {
        public int totalCalls() { return initialCalls + fallbackCalls + escalationCalls; }
    }
}
