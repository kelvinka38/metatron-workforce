package com.metatron.workforce.action;

import com.metatron.workforce.interaction.tools.ToolAdapter;
import com.metatron.workforce.interaction.tools.ToolRequest;
import com.metatron.workforce.interaction.tools.ToolResult;
import com.metatron.workforce.interaction.tools.WebSearchToolAdapter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GeneralWebResearchActionTest {
    @Test
    void governedResearchActionPreservesExternalSourceEvidence() {
        ToolAdapter fake = new ToolAdapter() {
            @Override public String capability() { return WebSearchToolAdapter.CAPABILITY; }

            @Override
            public ToolResult execute(ToolRequest request) {
                return new ToolResult(
                        request.requestId(), request.capability(), request.target(), request.operation(),
                        true,
                        "GROUNDED WEB ANSWER\nanswer=Vietnam regulator evidence",
                        List.of(
                                "https://example.gov.vn/regulation",
                                "https://journal.example.org/paper"));
            }
        };

        GeneralWebResearchAction action = new GeneralWebResearchAction(
                "WORKER-GENERAL-ENGINEERING", "authorization:test", fake);
        ActionFabric fabric = new ActionFabric(List.of(action));
        ActionFabric.ActionObservation observation = fabric.execute(new ActionFabric.ActionRequest(
                GeneralWebResearchAction.ACTION_REF,
                "WORKER-GENERAL-ENGINEERING",
                "assignment:test",
                "authorization:test",
                "objective:test",
                "research-step",
                "idempotency:test",
                false,
                Map.of("query", "Vietnam mother baby advertising KOL KOC research")));

        assertTrue(observation.success());
        assertEquals("2", observation.outputs().get("sourceCount"));
        assertTrue(observation.outputs().get("researchResult").contains("Vietnam regulator evidence"));
        assertTrue(observation.evidenceReferences().contains("https://example.gov.vn/regulation"));
        assertTrue(observation.evidenceReferences().contains("https://journal.example.org/paper"));
        assertTrue(observation.evidenceReferences().stream().anyMatch(value ->
                value.contains("action-fabric:action=research.web.search")
                        && value.contains("consequence=READ_ONLY")));
    }

    @Test
    void actionRejectsBlankResearchQuery() {
        ToolAdapter unused = new ToolAdapter() {
            @Override public String capability() { return WebSearchToolAdapter.CAPABILITY; }
            @Override public ToolResult execute(ToolRequest request) {
                return ToolResult.failure(request, "unused");
            }
        };
        GeneralWebResearchAction action = new GeneralWebResearchAction(
                "WORKER-GENERAL-ENGINEERING", "authorization:test", unused);
        ActionFabric fabric = new ActionFabric(List.of(action));

        assertThrows(IllegalArgumentException.class, () -> fabric.execute(new ActionFabric.ActionRequest(
                GeneralWebResearchAction.ACTION_REF,
                "WORKER-GENERAL-ENGINEERING",
                "assignment:test",
                "authorization:test",
                "objective:test",
                "research-step",
                "idempotency:test",
                false,
                Map.of())));
    }
}
