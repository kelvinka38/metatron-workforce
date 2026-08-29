package com.metatron.workforce.interaction.llm;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class ProviderTelemetryRegistryTest {
    @Test
    void recordsConcurrencyLatencyUsageQuotaAndConfiguredCostWithoutInventingUnknownValues() {
        ProviderTelemetryRegistry registry = new ProviderTelemetryRegistry(Map.of(
                LlmProvider.OPENAI, new ProviderTelemetryRegistry.Pricing(
                        new BigDecimal("2.0"), new BigDecimal("10.0"))));

        long started = registry.begin(LlmProvider.OPENAI);
        assertEquals(1, registry.snapshot(LlmProvider.OPENAI).activeRequests());
        LlmResponse response = new LlmResponse(
                LlmProvider.OPENAI, "model", "ok", "ref",
                new LlmUsage(100, 50, 150),
                Map.of("remaining_requests", "12", "remaining_tokens", "3456"));
        registry.success(LlmProvider.OPENAI, started, response);

        ProviderTelemetryRegistry.Snapshot snapshot = registry.snapshot(LlmProvider.OPENAI);
        assertEquals(0, snapshot.activeRequests());
        assertEquals(1, snapshot.successes());
        assertEquals(100, snapshot.totalInputTokens());
        assertEquals(50, snapshot.totalOutputTokens());
        assertEquals(150, snapshot.totalTokens());
        assertEquals(12, snapshot.remainingRequests());
        assertEquals(3456, snapshot.remainingTokens());
        assertTrue(snapshot.costKnown());
        assertEquals(700, snapshot.totalEstimatedCostMicros());
        assertTrue(snapshot.ewmaLatencyMillis() >= 0);

        assertFalse(registry.snapshot(LlmProvider.GOOGLE).costKnown());
        assertEquals(-1, registry.snapshot(LlmProvider.GOOGLE).lastEstimatedCostMicros());
    }

    @Test
    void rateLimitFailureCreatesTemporaryCooldownAndDoesNotLeakExceptionDetailsAsAuthority() {
        ProviderTelemetryRegistry registry = new ProviderTelemetryRegistry(Map.of());
        long started = registry.begin(LlmProvider.GOOGLE);
        registry.failure(LlmProvider.GOOGLE, started,
                new IllegalStateException("google_request_failed:429:quota exceeded"));

        ProviderTelemetryRegistry.Snapshot snapshot = registry.snapshot(LlmProvider.GOOGLE);
        assertEquals(1, snapshot.failures());
        assertTrue(snapshot.coolingDown());
        assertTrue(snapshot.quotaExhausted());
        assertEquals(0, snapshot.remainingRequests());
    }
}
