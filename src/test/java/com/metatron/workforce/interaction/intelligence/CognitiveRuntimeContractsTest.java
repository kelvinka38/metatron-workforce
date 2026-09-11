package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.MetatronInteraction;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.phase3.ActorRef;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CognitiveRuntimeContractsTest {
    @Test
    void canonicalEnvelopeKeepsTransportCorrelationSeparateFromInstitutionalIdentity() {
        MetatronInteraction interaction = new MetatronInteraction(
                new ActorRef("human-1", ActorRef.ActorType.HUMAN),
                new ActorRef("worker-1", ActorRef.ActorType.WORKER),
                "organization:1",
                "conversation:1",
                "telegram",
                "external-human",
                "external-conversation",
                "message-123",
                "hello");

        CanonicalRequestEnvelope envelope = CanonicalRequestEnvelope.from(interaction);

        assertEquals("interaction:telegram:message-123", envelope.requestId());
        assertEquals("human:human-1", envelope.requesterRef());
        assertEquals("worker:worker-1", envelope.workerRef());
        assertEquals("organization:1", envelope.authorityContextRef());
        assertEquals("telegram", envelope.channel());
    }

    @Test
    void institutionalContextFingerprintIsStableForEquivalentReferenceSets() {
        InstitutionalContextPackage first = InstitutionalContextPackage.resolve(
                "context:1",
                List.of("constitution", "sot"),
                List.of("repo:sot"),
                List.of("plan:v1"),
                List.of("runtime:a"),
                List.of("case:1"),
                List.of("evidence:2", "evidence:1"),
                List.of(),
                Map.of("repo:sot", "fresh"));
        InstitutionalContextPackage second = InstitutionalContextPackage.resolve(
                "context:2",
                List.of("constitution", "sot"),
                List.of("repo:sot"),
                List.of("plan:v1"),
                List.of("runtime:a"),
                List.of("case:1"),
                List.of("evidence:1", "evidence:2"),
                List.of(),
                Map.of("repo:sot", "fresh"));

        assertEquals(first.fingerprint(), second.fingerprint());
    }

    @Test
    void cognitionGatePrefersReusableOrDeterministicStateBeforeFrontier() {
        NormalizedRequest normalized = new NormalizedRequest(
                "answer objective", "", List.of(), IntelligenceDepth.ANALYZE,
                "answer", List.of(), List.of(), "", "",
                IntelligenceMode.REASONING, CollaborationMode.SINGLE, List.of(),
                DeterministicCapability.NONE, List.of(), List.of(), false,
                null, LlmProvider.OPENAI, CaseContinuity.NEW, "");
        CognitionNeedGate gate = new CognitionNeedGate();

        CognitionNeedGate.Decision artifactDecision = gate.evaluate(new CognitionNeedGate.Input(
                normalized, false, false, true, false));
        CognitionNeedGate.Decision deterministicDecision = gate.evaluate(new CognitionNeedGate.Input(
                normalized, true, false, false, false));

        assertEquals(CognitionNeedGate.Disposition.NOT_REQUIRED, artifactDecision.disposition());
        assertEquals("reusable_cognitive_artifact_available", artifactDecision.reason());
        assertEquals(CognitionNeedGate.Disposition.NOT_REQUIRED, deterministicDecision.disposition());
    }

    @Test
    void providerBudgetKeepsOneInitialCallForEveryDepth() {
        for (IntelligenceDepth depth : IntelligenceDepth.values()) {
            ProviderBudget budget = ProviderBudget.forDepth(depth);
            assertEquals(1, budget.maxInitialFrontierCalls());
            assertFalse(budget.multiModelAllowed());
            assertTrue(budget.allows(EscalationReason.PROVIDER_FAILURE));
        }
        assertEquals(0, ProviderBudget.deterministicZeroCall().maxTotalFrontierCalls());
    }

    @Test
    void explicitMultiModelBudgetAccountsForSemanticPassAndBoundedDeliberation() {
        ProviderBudget twoProviders = ProviderBudget.forDepth(IntelligenceDepth.ANALYZE)
                .withExplicitMultiModelRequest(2);
        ProviderBudget threeProviders = ProviderBudget.forDepth(IntelligenceDepth.DEEP)
                .withExplicitMultiModelRequest(3);

        assertTrue(twoProviders.multiModelAllowed());
        assertEquals(5, twoProviders.maxEscalationCalls(),
                "after the semantic initial call: 2 independent + 1 normalization + 2 challenges");
        assertEquals(7, threeProviders.maxEscalationCalls(),
                "after the semantic initial call: 3 independent + 1 normalization + 3 challenges");
        assertTrue(twoProviders.allows(EscalationReason.EXPLICIT_HUMAN_REQUEST));
        assertTrue(twoProviders.allows(EscalationReason.MATERIAL_CONTRADICTION));
    }

    @Test
    void cognitiveArtifactReuseRequiresMatchingFingerprintAndValidity() {
        String fingerprint = CognitiveFingerprint.sha256(
                "objective", "context-fp", List.of("evidence:b", "evidence:a"),
                "analysis", "answer");
        InMemoryCognitiveArtifactStore store = new InMemoryCognitiveArtifactStore();
        Instant now = Instant.now();
        store.save(new CognitiveArtifact(
                "artifact-1", "case-1", "answer", fingerprint,
                LlmProvider.OPENAI, "model", "result", List.of(), List.of("evidence:a"),
                List.of(), now, now.plusSeconds(60), true));

        assertTrue(store.findReusable(fingerprint, now.plusSeconds(1)).isPresent());
        assertTrue(store.findReusable("different", now).isEmpty());
        assertTrue(store.findReusable(fingerprint, now.plusSeconds(120)).isEmpty());
    }
}
