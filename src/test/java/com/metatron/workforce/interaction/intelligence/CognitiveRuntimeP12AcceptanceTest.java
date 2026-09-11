package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.MetatronInteraction;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmProviderClient;
import com.metatron.workforce.interaction.llm.LlmProviderRouter;
import com.metatron.workforce.interaction.llm.LlmRequest;
import com.metatron.workforce.interaction.llm.LlmResponse;
import com.metatron.workforce.phase3.ActorRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Comparative pre-production acceptance for the Founder-approved Cognitive Runtime invariants. */
final class CognitiveRuntimeP12AcceptanceTest {

    @Test
    void institutionalContextPrecedesCognitionAndOrdinaryAnswerUsesOneFrontierCall() {
        AtomicInteger providerCalls = new AtomicInteger();
        LlmProviderClient google = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }

            @Override public LlmResponse complete(LlmRequest request) {
                providerCalls.incrementAndGet();
                assertTrue(request.systemContext().contains("institutional_context_fingerprint="));
                assertTrue(request.systemContext().contains("FOUNDER_APPROVED_COGNITIVE_RUNTIME"));
                return semanticAnswer(LlmProvider.GOOGLE, "one call answer");
            }
        };
        LlmProviderRouter router = new LlmProviderRouter(List.of(google));
        FrontierSemanticInterpreter interpreter = new FrontierSemanticInterpreter(
                router, ignored -> "semantic-test", List.of(LlmProvider.GOOGLE), new ObjectMapper());
        MetatronInteraction interaction = interaction("telegram", "message-1");

        try (CognitiveRequestScope.Scope ignored = CognitiveRequestScope.open(interaction)) {
            NormalizedRequest normalized = interpreter.interpret(
                    "Phân tích ngắn gọn sự khác nhau giữa throughput và latency.", "", "telegram", (IntelligenceCase) null);

            assertEquals("one call answer", normalized.directResponse());
            assertTrue(normalized.canReturnPrimaryDirectly());
            assertEquals(1, providerCalls.get());
            String logicalRef = CanonicalRequestEnvelope.from(interaction).requestId();
            assertEquals(1, router.callTrace().forLogicalRequest(logicalRef).size());
            assertEquals(1, router.callBudgetRegistry().snapshot(logicalRef).initialCalls());
            assertEquals(1, router.callBudgetRegistry().snapshot(logicalRef).totalCalls());
        }
    }

    @Test
    void semanticProviderFailoverIsReasonCodedAndBoundedInsideOneLogicalRequest() {
        AtomicInteger googleCalls = new AtomicInteger();
        AtomicInteger openAiCalls = new AtomicInteger();
        LlmProviderClient google = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }
            @Override public LlmResponse complete(LlmRequest request) {
                googleCalls.incrementAndGet();
                throw new IllegalStateException("simulated provider outage");
            }
        };
        LlmProviderClient openAi = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.OPENAI; }
            @Override public LlmResponse complete(LlmRequest request) {
                openAiCalls.incrementAndGet();
                return semanticAnswer(LlmProvider.OPENAI, "fallback answer");
            }
        };
        LlmProviderRouter router = new LlmProviderRouter(List.of(google, openAi));
        FrontierSemanticInterpreter interpreter = new FrontierSemanticInterpreter(
                router, ignored -> "semantic-test", List.of(LlmProvider.GOOGLE, LlmProvider.OPENAI),
                new ObjectMapper());
        MetatronInteraction interaction = interaction("web", "message-2");

        try (CognitiveRequestScope.Scope ignored = CognitiveRequestScope.open(interaction)) {
            NormalizedRequest normalized = interpreter.interpret("Explain queue backpressure.", "", "web", (IntelligenceCase) null);
            assertEquals("fallback answer", normalized.directResponse());

            String logicalRef = CanonicalRequestEnvelope.from(interaction).requestId();
            var traces = router.callTrace().forLogicalRequest(logicalRef);
            assertEquals(2, traces.size());
            assertEquals("", traces.get(0).reasonCode());
            assertEquals(EscalationReason.PROVIDER_FAILURE.name(), traces.get(1).reasonCode());
            assertEquals(1, router.callBudgetRegistry().snapshot(logicalRef).initialCalls());
            assertEquals(1, router.callBudgetRegistry().snapshot(logicalRef).fallbackCalls());
            assertEquals(1, googleCalls.get());
            assertEquals(1, openAiCalls.get());
        }
    }

    @Test
    void transportDecorationDoesNotChangeReusableCognitiveFingerprint() {
        String telegramContext = "institutional_context_fingerprint=stable-context-fp\nInbound channel: telegram";
        String webContext = "institutional_context_fingerprint=stable-context-fp\nInbound channel: web";

        String first = CognitiveFingerprint.sha256(
                "same objective", CognitiveFingerprint.contextFingerprint(telegramContext),
                List.of("evidence:stable", "observation:telegram:msg-1"), "analysis", "answer");
        String second = CognitiveFingerprint.sha256(
                "same objective", CognitiveFingerprint.contextFingerprint(webContext),
                List.of("evidence:stable", "observation:web:msg-2"), "analysis", "answer");

        assertEquals(first, second, "transport-only channel decoration must not break artifact reuse");
    }

    @Test
    void deterministicHelpCompletesWithZeroFrontierProvidersConfigured() {
        MetatronIntelligenceResponder responder = new MetatronIntelligenceResponder(
                "", "", "", "AUTO", "", "", "", new ObjectMapper(),
                "", "", new InMemoryIntelligenceCaseStore(), ExecutionObjectiveHandoff.unavailable());

        String answer = responder.respond(
                "human-1", "/help", "message-help", "web",
                "conversation-help", "organization:1", "");

        assertTrue(answer.contains("Metatron Workforce online."));
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
