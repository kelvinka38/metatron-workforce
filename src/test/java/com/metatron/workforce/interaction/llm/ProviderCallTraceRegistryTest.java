package com.metatron.workforce.interaction.llm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderCallTraceRegistryTest {
    @Test
    void recordsSuccessfulCallWithLogicalCasePurposeAndUsage() {
        ProviderCallTraceRegistry trace = new ProviderCallTraceRegistry();
        LlmProviderClient client = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.OPENAI; }
            @Override public LlmResponse complete(LlmRequest request) {
                return new LlmResponse(
                        LlmProvider.OPENAI,
                        request.model(),
                        "ok",
                        "provider-ref",
                        new LlmUsage(11, 7, 18),
                        Map.of());
            }
        };
        LlmProviderRouter router = new LlmProviderRouter(
                List.of(client), new ProviderTelemetryRegistry(), trace);

        router.complete(new LlmRequest(
                LlmProvider.OPENAI,
                "test-model",
                "system",
                "human request",
                "logical-1",
                "case-1",
                "primary"));

        ProviderCallTraceRegistry.ProviderCallTrace record = trace.records().getFirst();
        assertTrue(record.success());
        assertEquals("logical-1", record.logicalRequestRef());
        assertEquals("case-1", record.caseRef());
        assertEquals("primary", record.purpose());
        assertEquals(11L, record.inputTokens());
        assertEquals(7L, record.outputTokens());
        assertEquals(18L, record.totalTokens());
        assertEquals(1L, trace.totalCallsForLogicalRequest("logical-1"));
    }

    @Test
    void recordsFailureWithoutInventingUsage() {
        ProviderCallTraceRegistry trace = new ProviderCallTraceRegistry();
        LlmProviderClient client = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }
            @Override public LlmResponse complete(LlmRequest request) {
                throw new IllegalStateException("quota unavailable");
            }
        };
        LlmProviderRouter router = new LlmProviderRouter(
                List.of(client), new ProviderTelemetryRegistry(), trace);

        try {
            router.complete(new LlmRequest(
                    LlmProvider.GOOGLE,
                    "test-model",
                    "system",
                    "human request",
                    "logical-2",
                    "case-2",
                    "fallback"));
        } catch (IllegalStateException expected) {
            // expected
        }

        ProviderCallTraceRegistry.ProviderCallTrace record = trace.records().getFirst();
        assertFalse(record.success());
        assertEquals(LlmUsage.UNKNOWN, record.inputTokens());
        assertEquals(LlmUsage.UNKNOWN, record.outputTokens());
        assertEquals(LlmUsage.UNKNOWN, record.totalTokens());
        assertTrue(record.failure().contains("quota unavailable"));
    }
}
