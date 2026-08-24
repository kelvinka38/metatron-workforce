package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IntelligenceFabricFailoverTest {

    @Test
    void single_mode_falls_back_when_first_provider_fails() {
        IntelligenceEngine engine = (provider, request) -> {
            if (provider == LlmProvider.OPENAI) {
                throw new IllegalStateException("429 quota");
            }
            return new LlmResponse(provider, "test-model", "fallback-answer", "ref-2");
        };

        IntelligenceFabric fabric = new IntelligenceFabric(
                request -> new IntelligencePlan(
                        request.requestId(),
                        true,
                        request.mode(),
                        CollaborationMode.SINGLE,
                        List.of(LlmProvider.OPENAI, LlmProvider.GOOGLE)),
                engine,
                (request, responses) -> responses.getFirst().text(),
                (request, responses, text) -> { }
        );

        IntelligenceResult result = fabric.execute(new IntelligenceRequest(
                "r1",
                "telegram:8647844045",
                IntelligenceMode.REASONING,
                CollaborationMode.SINGLE,
                "hello",
                "context",
                List.of("telegram:1"),
                "analysis",
                "LOW",
                "interactive",
                "standard",
                "telegram-human",
                "direct answer",
                List.of(),
                2));

        assertEquals("fallback-answer", result.text());
        assertEquals(LlmProvider.GOOGLE, result.providers().getFirst().provider());
    }
}
