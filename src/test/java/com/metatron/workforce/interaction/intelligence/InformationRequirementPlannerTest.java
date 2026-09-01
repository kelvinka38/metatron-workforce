package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class InformationRequirementPlannerTest {
    @Test
    void composesProtocolRequirementsWithoutUsingHumanTextKeywords() {
        InformationRequirementPlanner planner = new InformationRequirementPlanner(new AnalyticalProtocolRegistry());
        NormalizedRequest request = new NormalizedRequest(
                "understand why commercial performance deteriorated", "business performance", List.of("evidence required"),
                IntelligenceDepth.ANALYZE, "root cause report", List.of(), List.of("do not guess"),
                "current month vs previous month", "", IntelligenceMode.REASONING, CollaborationMode.SINGLE,
                List.of(AnalyticalProtocolType.PERFORMANCE, AnalyticalProtocolType.ROOT_CAUSE),
                DeterministicCapability.NONE, false, null, LlmProvider.GOOGLE, "");

        List<InformationRequirement> requirements = planner.plan(request);

        assertTrue(requirements.stream().anyMatch(r -> r.question().contains("current-period performance metrics")));
        assertTrue(requirements.stream().anyMatch(r -> r.question().contains("candidate drivers")));
        assertTrue(requirements.stream().allMatch(r -> r.status() == InformationRequirementStatus.MISSING));
        assertTrue(requirements.stream().allMatch(r -> !r.preferredSourceClasses().isEmpty()));
    }

    @Test
    void currentExternalRealityCreatesObjectiveBoundFreshEvidenceRequirement() {
        InformationRequirementPlanner planner = new InformationRequirementPlanner(new AnalyticalProtocolRegistry());
        NormalizedRequest request = new NormalizedRequest(
                "find today's SJC gold price in Vietnam", "SJC gold price", List.of(), IntelligenceDepth.ANALYZE,
                "direct natural-language answer", List.of(), List.of(), "today", "", IntelligenceMode.REASONING,
                CollaborationMode.SINGLE, List.of(), DeterministicCapability.NONE,
                true, null, LlmProvider.GOOGLE, "");

        List<InformationRequirement> requirements = planner.plan(request);

        assertEquals(1, requirements.size());
        InformationRequirement fresh = requirements.getFirst();
        assertEquals("find today's SJC gold price in Vietnam", fresh.question());
        assertEquals("current", fresh.freshnessRequirement());
        assertTrue(fresh.preferredSourceClasses().contains("web/external research"));
        assertNotEquals("current external evidence", fresh.question(),
                "freshness placeholder must never become the actual web-search subject");
    }
}
