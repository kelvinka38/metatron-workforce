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
    void currentExternalRealityPreservesNormalizedSubjectInFreshEvidenceRequirement() {
        InformationRequirementPlanner planner = new InformationRequirementPlanner(new AnalyticalProtocolRegistry());
        String objective = "Tra cứu giá vàng hôm nay tại Việt Nam với dữ liệu mới và cung cấp nguồn trích dẫn";
        NormalizedRequest request = new NormalizedRequest(
                objective, "giá vàng Việt Nam", List.of(), IntelligenceDepth.FAST,
                "giá hiện tại và nguồn", List.of(), List.of(), "today", "", IntelligenceMode.REASONING,
                CollaborationMode.SINGLE, List.of(), DeterministicCapability.NONE,
                true, null, LlmProvider.GOOGLE, "");

        List<InformationRequirement> requirements = planner.plan(request);

        assertEquals(1, requirements.size());
        InformationRequirement fresh = requirements.getFirst();
        assertEquals(objective, fresh.question());
        assertEquals("current", fresh.freshnessRequirement());
        assertTrue(fresh.preferredSourceClasses().contains("web/external research"));
        assertNotEquals("current external evidence", fresh.question());
    }

    @Test
    void freshQueriesRemainDomainSpecificAcrossFxAndWeather() {
        InformationRequirementPlanner planner = new InformationRequirementPlanner(new AnalyticalProtocolRegistry());
        List<String> objectives = List.of(
                "Tra cứu tỷ giá USD/VND hiện tại và nêu nguồn",
                "Thời tiết hiện tại ở Thành phố Hồ Chí Minh và nêu nguồn");

        for (String objective : objectives) {
            NormalizedRequest request = new NormalizedRequest(
                    objective, "external current state", List.of(), IntelligenceDepth.FAST,
                    "direct answer with source", List.of(), List.of(), "now", "", IntelligenceMode.REASONING,
                    CollaborationMode.SINGLE, List.of(), DeterministicCapability.NONE,
                    true, null, LlmProvider.GOOGLE, "");
            List<InformationRequirement> requirements = planner.plan(request);
            assertEquals(1, requirements.size());
            assertEquals(objective, requirements.getFirst().question());
            assertFalse(requirements.getFirst().question().equalsIgnoreCase("current external evidence"));
        }
    }
}
