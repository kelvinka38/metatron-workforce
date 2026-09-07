package com.metatron.workforce.workplace;

import com.metatron.workforce.interaction.intelligence.EvidenceBackedGovernance;
import com.metatron.workforce.interaction.intelligence.EvidencePreservingIntelligenceSynthesizer;
import com.metatron.workforce.interaction.intelligence.IntelligenceEngine;
import com.metatron.workforce.interaction.intelligence.IntelligenceFabric;
import com.metatron.workforce.interaction.intelligence.IntelligencePlanner;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeetingRoleDeliberatorProviderFailoverTest {
    @Test
    void preferredProviderCreditFailureFallsBackThroughInstitutionalIntelligence() {
        AtomicInteger openAiCalls = new AtomicInteger();
        AtomicInteger googleCalls = new AtomicInteger();
        IntelligenceEngine engine = (provider, request) -> {
            if (provider == LlmProvider.OPENAI) {
                openAiCalls.incrementAndGet();
                throw new IllegalStateException("openai_request_failed:429:no credits");
            }
            googleCalls.incrementAndGet();
            return new LlmResponse(LlmProvider.GOOGLE, "gemini-test",
                    "Strategy assessment from fallback provider", "google://request-1");
        };
        MeetingRoleDeliberator deliberator = MeetingRoleDeliberator.intelligenceBacked(
                fabric(List.of(LlmProvider.OPENAI, LlmProvider.GOOGLE), engine), 2);

        MeetingRoleDeliberator.Deliberation result = deliberator.deliberate(
                "Head of Strategy", "growth plan", "Founder conversation");

        assertEquals(1, openAiCalls.get());
        assertEquals(1, googleCalls.get());
        assertEquals("Strategy assessment from fallback provider", result.text());
        assertTrue(result.providerReference().startsWith(
                "provider:google:model:gemini-test:request:google://request-1"));
    }

    @Test
    void synthesisUsesSameInstitutionalIntelligenceFailoverPolicy() {
        IntelligenceEngine engine = (provider, request) -> {
            if (provider == LlmProvider.OPENAI) {
                throw new IllegalStateException("openai_request_failed:429:no credits");
            }
            return new LlmResponse(LlmProvider.ANTHROPIC, "claude-test",
                    "Preserved disagreement and recommendation", "anthropic://request-2");
        };
        MeetingRoleDeliberator deliberator = MeetingRoleDeliberator.intelligenceBacked(
                fabric(List.of(LlmProvider.OPENAI, LlmProvider.ANTHROPIC), engine), 2);

        MeetingRoleDeliberator.Deliberation result = deliberator.synthesize(
                "growth plan",
                List.of(new MeetingRecord.Contribution(
                        "role:head-of-strategy", "Head of Strategy",
                        "Grow carefully", "provider:google:model:test:request:1")),
                "Founder conversation");

        assertEquals("Preserved disagreement and recommendation", result.text());
        assertTrue(result.providerReference().startsWith("provider:anthropic:model:claude-test"));
    }

    @Test
    void meetingFailsClosedOnlyAfterInstitutionalIntelligenceExhaustsCapacity() {
        IntelligenceEngine engine = (provider, request) -> {
            throw new IllegalStateException(provider.name().toLowerCase() + "_failed");
        };
        MeetingRoleDeliberator deliberator = MeetingRoleDeliberator.intelligenceBacked(
                fabric(List.of(LlmProvider.OPENAI, LlmProvider.GOOGLE), engine), 2);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> deliberator.deliberate("Head of Finance", "growth plan", "context"));

        assertTrue(failure.getMessage().startsWith("meeting_intelligence_exhausted:"));
        assertTrue(failure.getCause().getMessage().contains("all selected intelligence providers failed"));
    }

    private static IntelligenceFabric fabric(List<LlmProvider> providers, IntelligenceEngine engine) {
        return new IntelligenceFabric(
                new IntelligencePlanner(request -> providers),
                engine,
                new EvidencePreservingIntelligenceSynthesizer(),
                new EvidenceBackedGovernance());
    }
}
