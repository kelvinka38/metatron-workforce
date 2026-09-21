package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.MetatronInteraction;
import com.metatron.workforce.interaction.llm.FrontierCallBudget;
import com.metatron.workforce.interaction.llm.LlmCallContext;
import com.metatron.workforce.interaction.llm.ProviderCallBudgetRegistry;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Synchronous per-interaction Cognitive Runtime scope.
 * It carries correlation/context only; it is not authority, memory, or authorization.
 */
public final class CognitiveRequestScope {
    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private CognitiveRequestScope() {}

    public static Scope open(MetatronInteraction interaction) {
        return open(interaction, 3, null);
    }

    /**
     * @param configuredProviderCount how many LLM providers are actually configured for this
     *     runtime (e.g. GOOGLE+ANTHROPIC+OPENAI+OLLAMA = 4). The ingress budget's bounded
     *     provider-failure fallback chain must be able to reach every one of them in a single
     *     cognitive pass, or a configured fallback (notably the local, no-paid-credit Ollama
     *     fallback) becomes structurally unreachable no matter how many providers precede it.
     * @param callBudgetRegistry when non-null, releases this logical request's bounded-call
     *     bookkeeping when the returned Scope closes, so a later retried pass for the same
     *     logical request (the same Telegram update reprocessed after a failure) starts with its
     *     own fresh, still-bounded budget instead of inheriting an already-exhausted one.
     */
    public static Scope open(MetatronInteraction interaction, int configuredProviderCount,
                             ProviderCallBudgetRegistry callBudgetRegistry) {
        Objects.requireNonNull(interaction, "interaction");
        if (configuredProviderCount < 1) {
            throw new IllegalArgumentException("configuredProviderCount must be positive");
        }
        if (CURRENT.get() != null) throw new IllegalStateException("cognitive_request_scope_already_open");
        CanonicalRequestEnvelope envelope = CanonicalRequestEnvelope.from(interaction);
        InstitutionalContextPackage context = new InstitutionalContextResolver.Default().resolve(
                new InstitutionalContextResolver.ContextResolutionRequest(
                        envelope,
                        List.of(
                                "UNIVERSAL/CONSTITUTION",
                                "CANONICAL_SOT",
                                "FOUNDER_APPROVED_COGNITIVE_RUNTIME",
                                "APPROVED_SPEC",
                                "APPROVED_IMPLEMENTATION_PLAN",
                                "CURRENT_REPOSITORY_RUNTIME_STATE",
                                "CURRENT_CASE_TASK_STATE",
                                "PRIOR_EVIDENCE"),
                        List.of(
                                "docs/ARCHITECTURE/INTELLIGENCE/04_COGNITIVE_RUNTIME_FINAL_PROPOSAL.md",
                                "docs/ARCHITECTURE/INTELLIGENCE/03_TRACEABILITY_MATRIX.md"),
                        List.of(
                                "docs/ARCHITECTURE/INTELLIGENCE/05_COGNITIVE_RUNTIME_DETAILED_PLAN.md",
                                "docs/ARCHITECTURE/INTELLIGENCE/06_COGNITIVE_RUNTIME_EXECUTION_PLAN.md"),
                        List.of(
                                "organization:" + interaction.organizationContextId(),
                                "worker:" + interaction.target().actorId()),
                        List.of(),
                        List.of(),
                        Map.of("interaction", "current")));

        // The bounded provider-failure fallback chain must be able to reach every configured
        // provider in this single pass -- one initial call plus one fallback per remaining
        // configured provider -- or a configured fallback (e.g. the local Ollama fallback) is
        // structurally unreachable no matter how many providers precede it.
        int maxFallbackCalls = Math.max(2, configuredProviderCount - 1);
        FrontierCallBudget ingressBudget = new FrontierCallBudget(
                1,
                maxFallbackCalls,
                1,
                false,
                Set.of(
                        EscalationReason.PROVIDER_FAILURE.name(),
                        EscalationReason.MATERIAL_CONTRADICTION.name(),
                        EscalationReason.INSUFFICIENT_EVIDENCE.name(),
                        EscalationReason.HIGH_CONSEQUENCE_CHALLENGE.name(),
                        EscalationReason.EXPLICIT_HUMAN_REQUEST.name(),
                        EscalationReason.CAPABILITY_MISMATCH.name(),
                        EscalationReason.NOVEL_INFORMATION_ACQUIRED.name()),
                true);
        String contextPrefix = renderContextPrefix(context);
        LlmCallContext.Scope llmScope = LlmCallContext.open(envelope.requestId(), contextPrefix, ingressBudget);

        State state = new State(envelope, context, llmScope, callBudgetRegistry);
        CURRENT.set(state);
        return new Scope(state);
    }

    public static String logicalRequestRef() {
        State state = CURRENT.get();
        return state == null ? "" : state.envelope.requestId();
    }

    public static CanonicalRequestEnvelope envelope() {
        State state = CURRENT.get();
        return state == null ? null : state.envelope;
    }

    public static InstitutionalContextPackage institutionalContext() {
        State state = CURRENT.get();
        return state == null ? null : state.context;
    }

    private static String renderContextPrefix(InstitutionalContextPackage context) {
        return "METATRON INSTITUTIONAL CONTEXT PACKAGE\n"
                + "institutional_context_fingerprint=" + context.fingerprint() + "\n"
                + "authority_chain=" + context.authorityChain() + "\n"
                + "canonical_refs=" + context.canonicalRefs() + "\n"
                + "plan_refs=" + context.planRefs() + "\n"
                + "runtime_refs=" + context.runtimeRefs() + "\n"
                + "conflicts=" + context.conflicts() + "\n"
                + "context_note=References are context/provenance only; they do not grant authority or authorization.";
    }

    private static final class State {
        final CanonicalRequestEnvelope envelope;
        final InstitutionalContextPackage context;
        final LlmCallContext.Scope llmScope;
        final ProviderCallBudgetRegistry callBudgetRegistry;

        State(CanonicalRequestEnvelope envelope, InstitutionalContextPackage context,
              LlmCallContext.Scope llmScope, ProviderCallBudgetRegistry callBudgetRegistry) {
            this.envelope = envelope;
            this.context = context;
            this.llmScope = llmScope;
            this.callBudgetRegistry = callBudgetRegistry;
        }
    }

    public static final class Scope implements AutoCloseable {
        private final State state;
        private boolean closed;

        private Scope(State state) {
            this.state = state;
        }

        @Override
        public void close() {
            if (closed) return;
            State current = CURRENT.get();
            if (current == state) CURRENT.remove();
            state.llmScope.close();
            if (state.callBudgetRegistry != null) {
                state.callBudgetRegistry.release(state.envelope.requestId());
            }
            closed = true;
        }
    }
}
