package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.knowledge.KnowledgeDocument;
import com.metatron.workforce.interaction.knowledge.KnowledgeQuery;
import com.metatron.workforce.interaction.knowledge.KnowledgeRetrievalService;
import com.metatron.workforce.interaction.knowledge.KnowledgeSource;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.tools.DefaultToolFabric;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class InformationRequirementPrioritizationTest {
    @Test
    void fastBudgetAcquiresHighestInformationValueRequirementFirst() {
        List<String> queries = new ArrayList<>();
        KnowledgeSource source = new KnowledgeSource() {
            @Override public String sourceId() { return "institutional.artifacts"; }
            @Override public KnowledgeDocument retrieve(KnowledgeQuery query) {
                queries.add(query.query());
                return new KnowledgeDocument("doc-" + queries.size(), sourceId(), "evidence",
                        "grounded", List.of("artifact:" + queries.size()));
            }
        };
        InformationRequirementAcquisitionService service = new InformationRequirementAcquisitionService(
                new KnowledgeRetrievalService(List.of(source)), new DefaultToolFabric(List.of()));

        InformationRequirement low = new InformationRequirement(
                "ir-low", "optional historical detail", "nice to have", InformationRequirementStatus.MISSING,
                List.of("institutional artifact"), List.of(), "historical", "grounded", "high", "extended",
                "read access", "minor completeness impact");
        InformationRequirement high = new InformationRequirement(
                "ir-high", "current material control state", "can change conclusion", InformationRequirementStatus.MISSING,
                List.of("institutional artifact"), List.of(), "current", "validated source-attributed",
                "low", "interactive", "read access", "may materially change or limit the conclusion");

        Instant now = Instant.now();
        IntelligenceCase intelligenceCase = new IntelligenceCase(
                "case-priority", "conversation:priority", "human:1", "evaluate material state",
                IntelligenceDepth.FAST, IntelligenceCaseStatus.INFORMATION_ASSESSMENT,
                List.of(low, high), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                "", "", List.of(), now, now);
        NormalizedRequest normalized = new NormalizedRequest(
                "evaluate material state", "system", List.of(), IntelligenceDepth.FAST,
                "direct answer", List.of(), List.of(), "current", "",
                IntelligenceMode.REASONING, CollaborationMode.SINGLE, List.of(), DeterministicCapability.NONE,
                false, null, LlmProvider.GOOGLE, "");

        var result = service.acquire(intelligenceCase, normalized, "human:1");

        assertEquals(1, queries.size());
        assertEquals(true, queries.getFirst().contains("current material control state"));
        assertEquals(InformationRequirementStatus.MISSING,
                result.intelligenceCase().informationRequirements().getFirst().status());
        assertEquals(InformationRequirementStatus.SATISFIED,
                result.intelligenceCase().informationRequirements().get(1).status());
    }
}
