package com.metatron.workforce.interaction.llm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProviderCallBudgetRegistryTest {
    @Test
    void hardBudgetCountsInitialFallbackAndEscalationAcrossOneLogicalRequest() {
        AtomicInteger calls = new AtomicInteger();
        LlmProviderClient client = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }
            @Override public LlmResponse complete(LlmRequest request) {
                calls.incrementAndGet();
                return new LlmResponse(provider(), request.model(), "ok", "provider-ref");
            }
        };
        ProviderCallBudgetRegistry budgets = new ProviderCallBudgetRegistry();
        LlmProviderRouter router = new LlmProviderRouter(
                List.of(client), new ProviderTelemetryRegistry(), new ProviderCallTraceRegistry(), budgets);
        FrontierCallBudget budget = new FrontierCallBudget(
                1, 1, 1, false,
                Set.of("NOVEL_INFORMATION_ACQUIRED", "INSUFFICIENT_EVIDENCE"), true);

        router.complete(request("logical-1", "", budget));
        router.complete(request("logical-1", "PROVIDER_FAILURE", budget));
        router.complete(request("logical-1", "NOVEL_INFORMATION_ACQUIRED", budget));

        assertEquals(3, calls.get());
        assertEquals(3, budgets.snapshot("logical-1").totalCalls());
        assertThrows(IllegalStateException.class,
                () -> router.complete(request("logical-1", "PROVIDER_FAILURE", budget)));
        assertEquals(3, calls.get(), "denied call must never reach provider transport");
    }

    @Test
    void multiModelReasonRequiresExplicitPermission() {
        LlmProviderClient client = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }
            @Override public LlmResponse complete(LlmRequest request) {
                return new LlmResponse(provider(), request.model(), "ok", "provider-ref");
            }
        };
        LlmProviderRouter router = new LlmProviderRouter(List.of(client));
        FrontierCallBudget budget = new FrontierCallBudget(
                1, 0, 2, false, Set.of("EXPLICIT_HUMAN_REQUEST"), true);
        router.complete(request("logical-2", "", budget));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> router.complete(request("logical-2", "EXPLICIT_HUMAN_REQUEST", budget)));
        assertEquals("frontier_multi_model_not_allowed", failure.getMessage());
    }

    private static LlmRequest request(String logicalRef, String reason, FrontierCallBudget budget) {
        return new LlmRequest(LlmProvider.GOOGLE, "model", "system", "input",
                logicalRef, "case-1", "test", reason, budget);
    }
}
