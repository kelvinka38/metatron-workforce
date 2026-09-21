package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.MetatronInteraction;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmProviderClient;
import com.metatron.workforce.interaction.llm.LlmProviderRouter;
import com.metatron.workforce.interaction.llm.LlmRequest;
import com.metatron.workforce.interaction.llm.LlmResponse;
import com.metatron.workforce.interaction.llm.ProviderCallBudgetRegistry;
import com.metatron.workforce.phase3.ActorRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ingress/semantic-provider admission acceptance for the production failure on Telegram update
 * 103337958: with GOOGLE+ANTHROPIC+OPENAI+OLLAMA configured, the bounded provider-failure fallback
 * chain must actually be able to reach OLLAMA (the local, no-paid-credit fallback) in a single
 * cognitive pass, and a retried pass for the same logical request must not inherit an
 * already-exhausted call budget.
 */
final class IngressProviderFailoverAcceptanceTest {

    /**
     * Acceptance test B: a non-canonical natural-language execution request that legitimately
     * requires semantic interpretation. The first three configured providers are forced to fail;
     * OLLAMA must actually be called as the final fallback, and the ingress call budget (scaled to
     * the real configured-provider count) must not reject it before transport.
     */
    @Test
    void nonCanonicalRequestReachesOllamaAsFinalConfiguredFallback() {
        AtomicInteger googleCalls = new AtomicInteger();
        AtomicInteger anthropicCalls = new AtomicInteger();
        AtomicInteger openAiCalls = new AtomicInteger();
        AtomicInteger ollamaCalls = new AtomicInteger();

        LlmProviderClient google = failingClient(LlmProvider.GOOGLE, googleCalls, "HTTP timeout");
        LlmProviderClient anthropic = failingClient(LlmProvider.ANTHROPIC, anthropicCalls, "400 insufficient credit");
        LlmProviderClient openAi = failingClient(LlmProvider.OPENAI, openAiCalls, "429 no credits");
        LlmProviderClient ollama = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.OLLAMA; }
            @Override public LlmResponse complete(LlmRequest request) {
                ollamaCalls.incrementAndGet();
                return semanticAnswer(LlmProvider.OLLAMA, "local fallback answer");
            }
        };

        LlmProviderRouter router = new LlmProviderRouter(List.of(google, anthropic, openAi, ollama));
        FrontierSemanticInterpreter interpreter = new FrontierSemanticInterpreter(
                router, provider -> "semantic-test",
                List.of(LlmProvider.GOOGLE, LlmProvider.ANTHROPIC, LlmProvider.OPENAI, LlmProvider.OLLAMA),
                new ObjectMapper());
        MetatronInteraction interaction = interaction("telegram", "update:103337958");

        try (CognitiveRequestScope.Scope ignored =
                     CognitiveRequestScope.open(interaction, 4, router.callBudgetRegistry())) {
            NormalizedRequest normalized = interpreter.interpret(
                    "Explain the tradeoffs between eventual consistency and strong consistency for a distributed queue.",
                    "", "telegram", (IntelligenceCase) null);

            assertEquals("local fallback answer", normalized.directResponse());
            assertEquals(1, googleCalls.get());
            assertEquals(1, anthropicCalls.get());
            assertEquals(1, openAiCalls.get());
            assertEquals(1, ollamaCalls.get(), "OLLAMA must actually be attempted as the final configured fallback");

            String logicalRef = CanonicalRequestEnvelope.from(interaction).requestId();
            ProviderCallBudgetRegistry.Snapshot snapshot = router.callBudgetRegistry().snapshot(logicalRef);
            assertEquals(1, snapshot.initialCalls());
            assertEquals(3, snapshot.fallbackCalls(),
                    "3 configured fallback providers must all be reachable in one bounded pass");
        }
    }

    /**
     * Acceptance test C: retry behavior must not reuse an already-exhausted call budget. A first
     * cognitive pass for a logical request that exhausts its bounded budget must not poison a later
     * retried pass for the exact same logical request (the same Telegram update reprocessed after a
     * failure) -- the scope must release its bookkeeping on close so the retry starts fresh.
     */
    @Test
    void retriedPassForSameLogicalRequestDoesNotInheritExhaustedBudget() {
        AtomicInteger googleCalls = new AtomicInteger();
        AtomicInteger anthropicCalls = new AtomicInteger();
        LlmProviderClient google = failingClient(LlmProvider.GOOGLE, googleCalls, "simulated outage");
        LlmProviderClient anthropic = failingClient(LlmProvider.ANTHROPIC, anthropicCalls, "simulated outage");
        LlmProviderRouter router = new LlmProviderRouter(List.of(google, anthropic));
        FrontierSemanticInterpreter interpreter = new FrontierSemanticInterpreter(
                router, provider -> "semantic-test", List.of(LlmProvider.GOOGLE, LlmProvider.ANTHROPIC),
                new ObjectMapper());
        MetatronInteraction interaction = interaction("telegram", "update:103337958");
        String logicalRef = CanonicalRequestEnvelope.from(interaction).requestId();

        try (CognitiveRequestScope.Scope firstPass =
                     CognitiveRequestScope.open(interaction, 2, router.callBudgetRegistry())) {
            IllegalStateException firstFailure = assertThrowsIllegalState(() -> interpreter.interpret(
                    "Please review the deployment plan and flag any risk.", "", "telegram", (IntelligenceCase) null));
            assertTrue(firstFailure.getMessage().contains("all semantic providers failed"),
                    "the first pass must genuinely exhaust configured providers through transport, not the budget layer");

            ProviderCallBudgetRegistry.Snapshot exhaustedDuringFirstPass = router.callBudgetRegistry().snapshot(logicalRef);
            assertEquals(1, exhaustedDuringFirstPass.initialCalls());
            assertEquals(1, exhaustedDuringFirstPass.fallbackCalls());
        }
        assertEquals(1, googleCalls.get());
        assertEquals(1, anthropicCalls.get());
        assertEquals(0, router.callBudgetRegistry().snapshot(logicalRef).totalCalls(),
                "closing the first pass's scope must release its bookkeeping immediately");

        try (CognitiveRequestScope.Scope retriedPass =
                     CognitiveRequestScope.open(interaction, 2, router.callBudgetRegistry())) {
            ProviderCallBudgetRegistry.Snapshot freshOnRetry = router.callBudgetRegistry().snapshot(logicalRef);
            assertEquals(0, freshOnRetry.totalCalls(),
                    "a retried pass for the same logical request must start with a fresh, unexhausted budget");

            IllegalStateException retryFailure = assertThrowsIllegalState(() -> interpreter.interpret(
                    "Please review the deployment plan and flag any risk.", "", "telegram", (IntelligenceCase) null));
            assertTrue(retryFailure.getMessage().contains("all semantic providers failed"),
                    "the retry must actually reach transport again through its own fresh budget, "
                            + "not fail instantly at frontier_call_budget_exhausted -- that would be fake recovery");
        }

        assertEquals(2, googleCalls.get(), "GOOGLE must be genuinely re-attempted on retry, not just counted");
        assertEquals(2, anthropicCalls.get(), "ANTHROPIC must be genuinely re-attempted on retry, not just counted");
    }

    private static IllegalStateException assertThrowsIllegalState(Runnable action) {
        try {
            action.run();
        } catch (IllegalStateException expected) {
            return expected;
        }
        throw new AssertionError("expected IllegalStateException");
    }

    private static LlmProviderClient failingClient(LlmProvider provider, AtomicInteger calls, String message) {
        return new LlmProviderClient() {
            @Override public LlmProvider provider() { return provider; }
            @Override public LlmResponse complete(LlmRequest request) {
                calls.incrementAndGet();
                throw new IllegalStateException(message);
            }
        };
    }

    private static MetatronInteraction interaction(String channel, String messageRef) {
        return new MetatronInteraction(
                new ActorRef("human-1", ActorRef.ActorType.HUMAN),
                new ActorRef("worker-1", ActorRef.ActorType.WORKER),
                "organization:1",
                "conversation:1",
                channel,
                "external-human",
                "external-conversation",
                messageRef,
                "test request");
    }

    private static LlmResponse semanticAnswer(LlmProvider provider, String directResponse) {
        String text = """
                {
                  "objective":"explain one bounded technical concept",
                  "target":"technical concept",
                  "constraints":[],
                  "requested_depth":"ANALYZE",
                  "requested_output":"direct natural-language answer",
                  "explicit_assumptions":[],
                  "explicit_prohibitions":[],
                  "temporal_context":"",
                  "unresolved_semantic_ambiguity":"",
                  "interaction_outcome":"ANSWER",
                  "evidence_scope":"NONE",
                  "mode":"REASONING",
                  "collaboration_mode":"SINGLE",
                  "analytical_protocols":[],
                  "deterministic_capability":"NONE",
                  "deterministic_computations":[],
                  "fresh_external_data_required":false,
                  "explicitly_requested_provider":null,
                  "execution_authorization":"NONE",
                  "case_continuity":"NEW",
                  "direct_response":"%s"
                }
                """.formatted(directResponse);
        return new LlmResponse(provider, "semantic-test", text, "provider-ref");
    }
}
