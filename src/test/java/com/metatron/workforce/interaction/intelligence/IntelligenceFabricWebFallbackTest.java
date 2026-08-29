package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.tools.DefaultToolFabric;
import com.metatron.workforce.interaction.tools.ToolAdapter;
import com.metatron.workforce.interaction.tools.ToolRequest;
import com.metatron.workforce.interaction.tools.ToolResult;
import com.metatron.workforce.interaction.tools.WebSearchToolAdapter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class IntelligenceFabricWebFallbackTest {

    @Test
    void returnsFreshWebEvidenceWhenSemanticContractRequiresExternalRealityAndEveryProviderFails() {
        IntelligencePlanner planner = new IntelligencePlanner(request ->
                List.of(LlmProvider.OPENAI, LlmProvider.GOOGLE, LlmProvider.ANTHROPIC));

        ToolAdapter web = new ToolAdapter() {
            @Override public String capability() { return WebSearchToolAdapter.CAPABILITY; }

            @Override
            public ToolResult execute(ToolRequest request) {
                return new ToolResult(
                        request.requestId(), request.capability(), request.target(), request.operation(), true,
                        "WEB SEARCH RESULTS\nquery=Giá vàng hôm nay\nretrieved_at=2026-08-28T08:54:10Z\n"
                                + "[1] Gold price today\nurl=https://example.test/gold\n"
                                + "snippet=Current gold market price evidence.",
                        List.of("https://example.test/gold"));
            }
        };

        IntelligenceFabric fabric = new IntelligenceFabric(
                planner,
                (provider, request) -> { throw new IllegalStateException("provider unavailable"); },
                (request, responses) -> "unused",
                (request, responses, text) -> {},
                new DefaultToolFabric(List.of(web)));

        IntelligenceRequest request = new IntelligenceRequest(
                "gold-live", "telegram-human", IntelligenceMode.DISCUSSION, CollaborationMode.SINGLE,
                "current gold price", "telegram", List.of(), "analysis", "LOW",
                "interactive-fast", "standard", "telegram-human", "direct answer",
                List.of(), 3, true);

        IntelligenceResult result = assertDoesNotThrow(() -> fabric.execute(request));

        assertTrue(result.text().contains("Gold price today"));
        assertTrue(result.text().contains("https://example.test/gold"));
        assertTrue(result.text().contains("Current gold market price evidence"));
        assertTrue(result.providerResults().isEmpty());
    }
}
