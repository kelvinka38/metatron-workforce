package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.MetatronInteraction;
import com.metatron.workforce.interaction.llm.FrontierCallBudget;
import com.metatron.workforce.interaction.llm.LlmCallContext;

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
        Objects.requireNonNull(interaction, "interaction");
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

        FrontierCallBudget ingressBudget = new FrontierCallBudget(
                1,
                2,
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

        State state = new State(envelope, context, llmScope);
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

        State(CanonicalRequestEnvelope envelope, InstitutionalContextPackage context,
              LlmCallContext.Scope llmScope) {
            this.envelope = envelope;
            this.context = context;
            this.llmScope = llmScope;
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
            closed = true;
        }
    }
}
