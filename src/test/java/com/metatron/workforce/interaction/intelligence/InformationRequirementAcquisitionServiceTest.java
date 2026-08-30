package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.knowledge.KnowledgeDocument;
import com.metatron.workforce.interaction.knowledge.KnowledgeQuery;
import com.metatron.workforce.interaction.knowledge.KnowledgeRetrievalService;
import com.metatron.workforce.interaction.knowledge.KnowledgeSource;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.tools.DefaultToolFabric;
import com.metatron.workforce.interaction.tools.ToolAdapter;
import com.metatron.workforce.interaction.tools.ToolRequest;
import com.metatron.workforce.interaction.tools.ToolResult;
import com.metatron.workforce.interaction.tools.WebSearchToolAdapter;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class InformationRequirementAcquisitionServiceTest {
    @Test
    void satisfiesRequirementFromInstitutionalArtifactBeforeExternalTool() {
        KnowledgeSource source = new KnowledgeSource() {
            @Override public String sourceId() { return "institutional.artifacts"; }
            @Override public KnowledgeDocument retrieve(KnowledgeQuery query) {
                return new KnowledgeDocument("doc-1", sourceId(), "audit", "gateway audit PASS",
                        List.of("institutional-artifact:work-1/execution.json"));
            }
        };
        AtomicInteger webCalls = new AtomicInteger();
        ToolAdapter web = new ToolAdapter() {
            @Override public String capability() { return WebSearchToolAdapter.CAPABILITY; }
            @Override public ToolResult execute(ToolRequest request) {
                webCalls.incrementAndGet();
                return ToolResult.success(request, "should not be called");
            }
        };

        InformationRequirementAcquisitionService service = new InformationRequirementAcquisitionService(
                new KnowledgeRetrievalService(List.of(source)), new DefaultToolFabric(List.of(web)));
        NormalizedRequest normalized = normalized(false);
        IntelligenceCase intelligenceCase = caseWith(requirement("ir-1", "gateway audit state",
                List.of("institutional artifact", "connected system/API")));

        var result = service.acquire(intelligenceCase, normalized, "human:1");

        assertEquals(1, result.satisfiedRequirements());
        assertEquals(0, result.unresolvedRequirements());
        assertEquals(0, webCalls.get());
        assertEquals(InformationRequirementStatus.SATISFIED,
                result.intelligenceCase().informationRequirements().getFirst().status());
        assertTrue(result.intelligenceCase().evidenceReferences()
                .contains("institutional-artifact:work-1/execution.json"));
        assertTrue(result.groundedContext().contains("gateway audit PASS"));
    }

    @Test
    void acquiresFreshExternalEvidenceWithRequirementFocusedQuery() {
        AtomicInteger webCalls = new AtomicInteger();
        List<String> queries = new ArrayList<>();
        ToolAdapter web = new ToolAdapter() {
            @Override public String capability() { return WebSearchToolAdapter.CAPABILITY; }
            @Override public ToolResult execute(ToolRequest request) {
                webCalls.incrementAndGet();
                queries.add(request.input());
                return new ToolResult(request.requestId(), request.capability(), request.target(), request.operation(),
                        true, "CURRENT EXTERNAL DATA\nmetric=42", List.of("https://example.test/evidence"));
            }
        };
        InformationRequirementAcquisitionService service = new InformationRequirementAcquisitionService(
                new KnowledgeRetrievalService(List.of()), new DefaultToolFabric(List.of(web)));
        IntelligenceCase intelligenceCase = caseWith(requirement("ir-1", "current gold price in Vietnam today",
                List.of("web/external research")));

        var result = service.acquire(intelligenceCase, normalized(true), "human:1");

        assertEquals(1, webCalls.get());
        assertEquals("current gold price in Vietnam today", queries.getFirst());
        assertFalse(queries.getFirst().contains("objective="));
        assertTrue(result.externalEvidenceAcquired());
        assertEquals(1, result.satisfiedRequirements());
        assertEquals(InformationRequirementStatus.SATISFIED,
                result.intelligenceCase().informationRequirements().getFirst().status());
        assertTrue(result.intelligenceCase().evidenceReferences().contains("https://example.test/evidence"));
        assertTrue(result.groundedFallback().contains("metric=42"));
    }

    @Test
    void acquiresEachExternalRequirementWithItsOwnQuestionAndEvidence() {
        AtomicInteger webCalls = new AtomicInteger();
        List<String> queries = new ArrayList<>();
        ToolAdapter web = new ToolAdapter() {
            @Override public String capability() { return WebSearchToolAdapter.CAPABILITY; }
            @Override public ToolResult execute(ToolRequest request) {
                int call = webCalls.incrementAndGet();
                queries.add(request.input());
                return new ToolResult(request.requestId(), request.capability(), request.target(), request.operation(),
                        true, "CURRENT EXTERNAL DATA\nquery=" + request.input(),
                        List.of("https://example.test/evidence-" + call));
            }
        };
        InformationRequirementAcquisitionService service = new InformationRequirementAcquisitionService(
                new KnowledgeRetrievalService(List.of()), new DefaultToolFabric(List.of(web)));
        IntelligenceCase intelligenceCase = caseWith(List.of(
                requirement("ir-gateway", "current gateway state", List.of("web/external research")),
                requirement("ir-deploy", "current deployment state", List.of("web/external research"))));

        var result = service.acquire(intelligenceCase, normalized(true), "human:1");

        assertEquals(2, webCalls.get());
        assertEquals(2, result.satisfiedRequirements());
        assertEquals(0, result.unresolvedRequirements());
        assertTrue(queries.contains("current gateway state"));
        assertTrue(queries.contains("current deployment state"));
        assertTrue(result.intelligenceCase().evidenceReferences().contains("https://example.test/evidence-1"));
        assertTrue(result.intelligenceCase().evidenceReferences().contains("https://example.test/evidence-2"));
        assertTrue(result.groundedContext().contains("requirement_id=ir-gateway"));
        assertTrue(result.groundedContext().contains("requirement_id=ir-deploy"));
    }

    @Test
    void failedExternalRequirementDoesNotPoisonAnotherRequirement() {
        AtomicInteger webCalls = new AtomicInteger();
        ToolAdapter web = new ToolAdapter() {
            @Override public String capability() { return WebSearchToolAdapter.CAPABILITY; }
            @Override public ToolResult execute(ToolRequest request) {
                webCalls.incrementAndGet();
                if (request.input().startsWith("current gateway state")) {
                    return ToolResult.failure(request, "source unavailable");
                }
                return new ToolResult(request.requestId(), request.capability(), request.target(), request.operation(),
                        true, "CURRENT EXTERNAL DATA\ndeployment=healthy",
                        List.of("https://example.test/deployment"));
            }
        };
        InformationRequirementAcquisitionService service = new InformationRequirementAcquisitionService(
                new KnowledgeRetrievalService(List.of()), new DefaultToolFabric(List.of(web)));
        IntelligenceCase intelligenceCase = caseWith(List.of(
                requirement("ir-gateway", "current gateway state", List.of("web/external research")),
                requirement("ir-deploy", "current deployment state", List.of("web/external research"))));

        var result = service.acquire(intelligenceCase, normalized(true), "human:1");

        assertEquals(2, webCalls.get());
        assertEquals(1, result.satisfiedRequirements());
        assertEquals(1, result.unresolvedRequirements());
        assertTrue(result.externalEvidenceAcquired());
        assertEquals(InformationRequirementStatus.UNRESOLVABLE, status(result, "ir-gateway"));
        assertEquals(InformationRequirementStatus.SATISFIED, status(result, "ir-deploy"));
        assertTrue(result.groundedFallback().contains("deployment=healthy"));
        assertFalse(result.groundedFallback().contains("source unavailable"));
    }

    private static InformationRequirementStatus status(InformationRequirementAcquisitionService.AcquisitionResult result,
                                                       String requirementId) {
        return result.intelligenceCase().informationRequirements().stream()
                .filter(requirement -> requirement.requirementId().equals(requirementId))
                .findFirst().orElseThrow().status();
    }

    private static InformationRequirement requirement(String id, String question, List<String> sources) {
        return new InformationRequirement(id, question, "required for audit",
                InformationRequirementStatus.MISSING, sources, List.of(), "current", "grounded",
                "low", "interactive", "read access", "material");
    }

    private static IntelligenceCase caseWith(InformationRequirement requirement) { return caseWith(List.of(requirement)); }

    private static IntelligenceCase caseWith(List<InformationRequirement> requirements) {
        Instant now = Instant.now();
        return new IntelligenceCase("case-1", "conversation:1", "human:1", "audit gateway",
                IntelligenceDepth.ANALYZE, IntelligenceCaseStatus.INFORMATION_ASSESSMENT,
                requirements, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                "", "", List.of(), now, now);
    }

    private static NormalizedRequest normalized(boolean fresh) {
        return new NormalizedRequest("audit gateway", "gateway", List.of(), IntelligenceDepth.ANALYZE,
                "direct natural-language answer", List.of(), List.of(), "current", "",
                IntelligenceMode.REASONING, CollaborationMode.SINGLE,
                List.of(AnalyticalProtocolType.AUDIT), DeterministicCapability.NONE, fresh,
                null, LlmProvider.GOOGLE, "");
    }
}
