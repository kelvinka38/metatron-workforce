package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmProviderClient;
import com.metatron.workforce.interaction.llm.LlmProviderRouter;
import com.metatron.workforce.interaction.llm.LlmRequest;
import com.metatron.workforce.interaction.llm.LlmResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class ApprovalDeferredExecutionRoutingTest {

    private static final String DEFERRED =
            "Take it as your own company. Propose and design the org chart, budget, demand and plan for Gateway. " +
            "Once I approve then execute.";

    @Test
    void explicitApprovalPreconditionIsAConservativeExecutionDenyGate() {
        assertTrue(ApprovalDeferredExecutionGuard.requiresApprovalBeforeExecution(DEFERRED));
        assertTrue(ApprovalDeferredExecutionGuard.requiresApprovalBeforeExecution(
                "Thiết kế proposal trước. Sau khi tao duyệt thì mới triển khai."));
        assertTrue(ApprovalDeferredExecutionGuard.requiresApprovalBeforeExecution(
                "Wait for my approval before you deploy it."));

        assertFalse(ApprovalDeferredExecutionGuard.requiresApprovalBeforeExecution(
                "Propose an org chart and budget."));
        assertFalse(ApprovalDeferredExecutionGuard.requiresApprovalBeforeExecution(
                "Approved. Execute the plan now."));
        assertFalse(ApprovalDeferredExecutionGuard.requiresApprovalBeforeExecution(
                "Deploy the approved release now."));
    }

    @Test
    void semanticProviderCannotPromoteApprovalGatedProposalIntoDurableWork() {
        LlmProviderClient provider = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }

            @Override public LlmResponse complete(LlmRequest request) {
                assertTrue(request.systemContext().contains("AFTER_HUMAN_APPROVAL is NOT DURABLE_WORK now"));
                assertTrue(request.systemContext().contains("Do not create, admit, enqueue or accept a Workforce Objective before that later approval"));
                return new LlmResponse(LlmProvider.GOOGLE, "semantic-test", """
                        {
                          "objective":"design Gateway organization, budget, demand and execution plan for Human review",
                          "target":"Gateway",
                          "constraints":["execute only after later Human approval"],
                          "requested_depth":"ANALYZE",
                          "requested_output":"proposal with org chart, budget, demand and plan",
                          "explicit_assumptions":[],
                          "explicit_prohibitions":["do not execute before Human approval"],
                          "temporal_context":"",
                          "unresolved_semantic_ambiguity":"",
                          "interaction_outcome":"DURABLE_WORK",
                          "evidence_scope":"INSTITUTIONAL",
                          "mode":"EXECUTION",
                          "collaboration_mode":"SINGLE",
                          "analytical_protocols":["IMPROVEMENT"],
                          "deterministic_capability":"NONE",
                          "deterministic_computations":[],
                          "fresh_external_data_required":false,
                          "execution_authorization":"NOW",
                          "explicitly_requested_provider":null,
                          "case_continuity":"NEW",
                          "direct_response":""
                        }
                        """, "semantic-ref");
            }
        };

        FrontierSemanticInterpreter interpreter = new FrontierSemanticInterpreter(
                new LlmProviderRouter(List.of(provider)), ignored -> "semantic-test",
                List.of(LlmProvider.GOOGLE), new ObjectMapper());

        NormalizedRequest normalized = interpreter.interpret(DEFERRED, "", "telegram");

        assertEquals(IntelligenceMode.REASONING, normalized.mode(),
                "approval-gated proposal must remain an in-interaction reasoning product");
        assertTrue(normalized.executionWorkPlan().isEmpty());
        assertEquals("proposal with org chart, budget, demand and plan", normalized.requestedOutput());
    }

    @Test
    void approvalGatedPlanningCaseIsDurableButIsNotAWorkforceObjective() {
        Instant now = Instant.parse("2026-09-10T00:00:00Z");
        IntelligenceCase planning = new IntelligenceCase(
                "case-planning", "conversation:founder", "human:primary",
                "design Gateway plan", IntelligenceDepth.ANALYZE,
                IntelligenceCaseStatus.REASONING, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), "", "", List.of(), now, now);

        IntelligenceCase persisted = MetatronIntelligenceResponder.resultCase(
                planning, "proposal body", List.of("observation:telegram:1"), true);

        assertEquals(IntelligenceCaseStatus.AWAITING_HUMAN_APPROVAL, persisted.status());
        assertEquals("proposal body", persisted.latestConclusion());
        assertTrue(persisted.externalInstitutionalReferences().isEmpty(),
                "planning Case must not invent Objective/Assignment/Execution references");
    }

    @Test
    void deterministicFounderExecutionControlCannotBypassDeferredApproval() {
        AtomicInteger handoffs = new AtomicInteger();
        ExecutionObjectiveHandoff handoff = (humanId, organizationContextId, caseId, conversationId,
                                             externalMessageReference, channel, request) -> {
            handoffs.incrementAndGet();
            return new ExecutionObjectiveHandoff.HandoffReceipt(
                    true, "objective:wrong", "worker", "queue", "ACCEPTED", "ADMITTED", "wrong");
        };

        MetatronIntelligenceResponder responder = new MetatronIntelligenceResponder(
                "", "", "", "AUTO", "", "", "", new ObjectMapper(),
                "", "", new InMemoryIntelligenceCaseStore(), handoff);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                responder.respond(
                        "human-primary",
                        "Create for me a workforce, role Gateway Head. Propose the structure first; once I approve then execute.",
                        "telegram:update:approval-gate",
                        "telegram",
                        "conversation:human:human-primary",
                        "organization:metatron",
                        ""));

        assertEquals("semantic_provider_required", failure.getMessage());
        assertEquals(0, handoffs.get(), "deferred approval must suppress deterministic execution fallback");
    }

    @Test
    void canonicalObjectiveControlAlsoCannotBypassDeferredApproval() {
        AtomicInteger handoffs = new AtomicInteger();
        ExecutionObjectiveHandoff handoff = (humanId, organizationContextId, caseId, conversationId,
                                             externalMessageReference, channel, request) -> {
            handoffs.incrementAndGet();
            return new ExecutionObjectiveHandoff.HandoffReceipt(
                    true, "objective:wrong", "worker", "queue", "ACCEPTED", "ADMITTED", "wrong");
        };

        MetatronIntelligenceResponder responder = new MetatronIntelligenceResponder(
                "", "", "", "AUTO", "", "", "", new ObjectMapper(),
                "", "", new InMemoryIntelligenceCaseStore(), handoff);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                responder.respond(
                        "human-primary",
                        "Take ownership of one Objective: design the Gateway organization and budget; after I approve, execute it.",
                        "telegram:update:objective-approval-gate",
                        "telegram",
                        "conversation:human:human-primary",
                        "organization:metatron",
                        ""));

        assertEquals("semantic_provider_required", failure.getMessage());
        assertEquals(0, handoffs.get(), "explicit Objective grammar cannot override an approval precondition");
    }
}
