package com.metatron.workforce.interaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.intelligence.ExecutionObjectiveHandoff;
import com.metatron.workforce.interaction.intelligence.FrontierSemanticInterpreter;
import com.metatron.workforce.interaction.intelligence.NormalizedRequest;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmProviderClient;
import com.metatron.workforce.interaction.llm.LlmProviderRouter;
import com.metatron.workforce.interaction.llm.LlmRequest;
import com.metatron.workforce.interaction.llm.LlmResponse;
import com.metatron.workforce.phase3.ActorRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerInstructionAdmissionServiceTest {

    @Test
    void executionInstructionTargetsSelectedCanonicalWorkerThroughExistingHandoff() {
        FrontierSemanticInterpreter semantic = semanticInterpreter("EXECUTION", "DURABLE_WORK", "NOW", "implement the requested change");
        AtomicReference<String> owner = new AtomicReference<>();
        AtomicInteger targetedCalls = new AtomicInteger();
        ExecutionObjectiveHandoff handoff = new ExecutionObjectiveHandoff() {
            @Override
            public HandoffReceipt submit(String humanId, String organizationContextId, String caseId,
                                         String conversationId, String externalMessageReference,
                                         String channel, NormalizedRequest request) {
                throw new AssertionError("generic Head handoff must not be used for a selected Worker instruction");
            }

            @Override
            public HandoffReceipt submitToWorker(String ownerWorkerId, String humanId,
                                                  String organizationContextId, String caseId,
                                                  String conversationId, String externalMessageReference,
                                                  String channel, NormalizedRequest request) {
                targetedCalls.incrementAndGet();
                owner.set(ownerWorkerId);
                return new HandoffReceipt(true, "objective:worker:test", ownerWorkerId,
                        "queue:worker:test", "ACCEPTED", "ACCEPTED",
                        "OBJECTIVE_ACCEPTED_FOR_AUTONOMOUS_MANAGEMENT");
            }
        };
        WorkerInstructionAdmissionService service = new WorkerInstructionAdmissionService(semantic, handoff);

        var result = service.evaluate(interaction("Implement the requested change now."),
                "WORKER-GATEWAY-DIRECTOR", "worker_status=ACTIVE");

        assertTrue(result.isPresent());
        assertTrue(result.get().admitted());
        assertEquals("WORKER-GATEWAY-DIRECTOR", owner.get());
        assertEquals(1, targetedCalls.get());
        assertEquals("objective:worker:test", result.get().objectiveId());
        assertTrue(result.get().evidenceReferences().contains("worker-objective-owner:WORKER-GATEWAY-DIRECTOR"));
    }

    @Test
    void executeNowAuthorizationCannotBeDowngradedToConversationOnly() {
        FrontierSemanticInterpreter semantic = semanticInterpreter("REASONING", "ANSWER", "NOW", "prove Metatron production works safely");
        AtomicReference<String> owner = new AtomicReference<>();
        ExecutionObjectiveHandoff handoff = new ExecutionObjectiveHandoff() {
            @Override
            public HandoffReceipt submit(String humanId, String organizationContextId, String caseId,
                                         String conversationId, String externalMessageReference,
                                         String channel, NormalizedRequest request) {
                throw new AssertionError("generic handoff must not be used for selected Worker instruction");
            }

            @Override
            public HandoffReceipt submitToWorker(String ownerWorkerId, String humanId,
                                                  String organizationContextId, String caseId,
                                                  String conversationId, String externalMessageReference,
                                                  String channel, NormalizedRequest request) {
                owner.set(ownerWorkerId);
                assertEquals(com.metatron.workforce.interaction.intelligence.IntelligenceMode.EXECUTION, request.mode());
                return new HandoffReceipt(true, "objective:worker:now", ownerWorkerId,
                        "queue:worker:now", "ACCEPTED", "ACCEPTED",
                        "OBJECTIVE_ACCEPTED_FOR_AUTONOMOUS_MANAGEMENT");
            }
        };
        WorkerInstructionAdmissionService service = new WorkerInstructionAdmissionService(semantic, handoff);

        var result = service.evaluate(interaction("Prove Metatron production works safely now."),
                "WORKER-GENERAL-ENGINEERING", "worker_status=ACTIVE");

        assertTrue(result.isPresent());
        assertTrue(result.get().admitted());
        assertEquals("WORKER-GENERAL-ENGINEERING", owner.get());
    }

    @Test
    void ordinaryWorkerConversationDoesNotBecomeDurableWork() {
        FrontierSemanticInterpreter semantic = semanticInterpreter("DISCUSSION", "ANSWER", "NONE", "answer the Human");
        AtomicInteger targetedCalls = new AtomicInteger();
        ExecutionObjectiveHandoff handoff = new ExecutionObjectiveHandoff() {
            @Override
            public HandoffReceipt submit(String humanId, String organizationContextId, String caseId,
                                         String conversationId, String externalMessageReference,
                                         String channel, NormalizedRequest request) {
                throw new AssertionError("generic handoff must not be used");
            }

            @Override
            public HandoffReceipt submitToWorker(String ownerWorkerId, String humanId,
                                                  String organizationContextId, String caseId,
                                                  String conversationId, String externalMessageReference,
                                                  String channel, NormalizedRequest request) {
                targetedCalls.incrementAndGet();
                return HandoffReceipt.blocked("unexpected");
            }
        };
        WorkerInstructionAdmissionService service = new WorkerInstructionAdmissionService(semantic, handoff);

        var result = service.evaluate(interaction("What did we discuss yesterday?"),
                "WORKER-GATEWAY-DIRECTOR", "worker_status=ACTIVE");

        assertTrue(result.isEmpty());
        assertEquals(0, targetedCalls.get());
    }

    private static FrontierSemanticInterpreter semanticInterpreter(
            String mode, String outcome, String executionAuthorization, String objective) {
        LlmProviderClient google = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }
            @Override public LlmResponse complete(LlmRequest request) {
                String directResponse = "ANSWER".equals(outcome) ? "Worker discussion answer." : "";
                String text = """
                        {
                          "objective":"%s",
                          "target":"selected canonical Worker",
                          "constraints":[],
                          "requested_depth":"ANALYZE",
                          "requested_output":"durable result",
                          "explicit_assumptions":[],
                          "explicit_prohibitions":["do not fabricate execution"],
                          "temporal_context":"",
                          "unresolved_semantic_ambiguity":"",
                          "interaction_outcome":"%s",
                          "evidence_scope":"NONE",
                          "mode":"%s",
                          "collaboration_mode":"SINGLE",
                          "analytical_protocols":[],
                          "deterministic_capability":"NONE",
                          "deterministic_computations":[],
                          "fresh_external_data_required":false,
                          "explicitly_requested_provider":null,
                          "execution_authorization":"%s",
                          "case_continuity":"NEW",
                          "direct_response":"%s"
                        }
                        """.formatted(objective, outcome, mode, executionAuthorization, directResponse);
                return new LlmResponse(LlmProvider.GOOGLE, "semantic-test", text, "semantic-ref");
            }
        };
        return new FrontierSemanticInterpreter(
                new LlmProviderRouter(List.of(google)), provider -> "semantic-test",
                List.of(LlmProvider.GOOGLE), new ObjectMapper());
    }

    private static MetatronInteraction interaction(String text) {
        return new MetatronInteraction(
                new ActorRef("human-primary", ActorRef.ActorType.HUMAN),
                new ActorRef("metatron-workforce", ActorRef.ActorType.WORKER),
                "organization:metatron",
                "conversation:human:human-primary",
                "telegram",
                "telegram:user:1",
                "telegram:chat:1",
                "telegram:update:worker-instruction",
                text);
    }
}
