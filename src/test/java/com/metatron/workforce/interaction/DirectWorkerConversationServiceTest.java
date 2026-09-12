package com.metatron.workforce.interaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.interaction.intelligence.ExecutionObjectiveHandoff;
import com.metatron.workforce.interaction.intelligence.FrontierSemanticInterpreter;
import com.metatron.workforce.interaction.intelligence.NormalizedRequest;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmProviderClient;
import com.metatron.workforce.interaction.llm.LlmProviderRouter;
import com.metatron.workforce.interaction.llm.LlmRequest;
import com.metatron.workforce.interaction.llm.LlmResponse;
import com.metatron.workforce.interaction.memory.PersistentWorkerConversationMemoryStore;
import com.metatron.workforce.management.ManagementAutonomyService;
import com.metatron.workforce.runtime.RuntimeCapacityCoordinator;
import com.metatron.workforce.runtime.RuntimeRegistry;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import com.metatron.workforce.phase3.ActorRef;
import com.metatron.workforce.work.WorkService;
import com.metatron.workforce.workplace.MeetingWorkerDirectory;
import com.metatron.workforce.workplace.WorkerConversationGateway;
import com.metatron.workforce.workplace.WorkplaceControlRoomService;
import com.metatron.workforce.workplace.WorkplaceDashboardService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DirectWorkerConversationServiceTest {
    @TempDir Path temp;

    @Test
    void telegramWorkerConversationContinuesInWorkplaceWithSameDurableMemory() {
        Fixture fixture = fixture();
        List<String> contexts = new ArrayList<>();
        WorkerConversationGateway gateway = new WorkerConversationGateway() {
            @Override
            public Reply converse(String workerId, String role, String userMessage, String context) {
                return converse(workerId, role, userMessage, context, List.of());
            }

            @Override
            public Reply converse(String workerId, String role, String userMessage, String context,
                                  List<String> trustedExecutionEvidence) {
                contexts.add(context);
                String answer = userMessage.contains("threshold")
                        ? "I will remember the Gateway threshold is 180 ms."
                        : "The Gateway threshold we discussed is 180 ms.";
                return new Reply(answer, "req-" + contexts.size(), List.of("institutional-source:test"), "runtime-1");
            }
        };

        DirectWorkerConversationService service = new DirectWorkerConversationService(
                fixture.bindings(), fixture.memory(), fixture.controlRoom(), fixture.directory(), gateway);

        var select = service.handle(interaction("talk to Head of Gateway", "msg-1"));
        assertTrue(select.isPresent());
        assertTrue(select.get().text().contains("WORKER-GATEWAY-DIRECTOR"));
        assertTrue(select.get().text().contains("memory_turns=0"));

        var telegramReply = service.handle(interaction(
                "For this topic remember the Gateway threshold is 180 ms.", "msg-2"));
        assertTrue(telegramReply.isPresent());
        assertEquals("WORKER-GATEWAY-DIRECTOR", telegramReply.get().workerId());

        var workplaceReply = service.converse(
                "human-primary", "WORKER-GATEWAY-DIRECTOR",
                "What threshold did we discuss?", "workplace");

        assertEquals("WORKER-GATEWAY-DIRECTOR", workplaceReply.workerId());
        assertTrue(contexts.getLast().contains("DURABLE WORKER MEMORY"));
        assertTrue(contexts.getLast().contains("180 ms"));
        assertEquals(2, fixture.memory().turnCount("human-primary", "WORKER-GATEWAY-DIRECTOR"));
    }

    @Test
    void workplaceDirectWorkerExecutionInstructionUsesSameTargetedWorkAdmissionPath() {
        Fixture fixture = fixture();
        LlmProviderClient google = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }
            @Override public LlmResponse complete(LlmRequest request) {
                return new LlmResponse(LlmProvider.GOOGLE, "semantic-test", """
                        {
                          "objective":"run the delegated Gateway audit",
                          "target":"Gateway",
                          "constraints":[],
                          "requested_depth":"ANALYZE",
                          "requested_output":"durable result",
                          "explicit_assumptions":[],
                          "explicit_prohibitions":["do not fabricate execution"],
                          "temporal_context":"",
                          "unresolved_semantic_ambiguity":"",
                          "interaction_outcome":"DURABLE_WORK",
                          "evidence_scope":"NONE",
                          "mode":"EXECUTION",
                          "collaboration_mode":"SINGLE",
                          "analytical_protocols":[],
                          "deterministic_capability":"NONE",
                          "deterministic_computations":[],
                          "fresh_external_data_required":false,
                          "explicitly_requested_provider":null,
                          "execution_authorization":"NOW",
                          "case_continuity":"NEW",
                          "direct_response":""
                        }
                        """, "semantic-ref");
            }
        };
        FrontierSemanticInterpreter semantic = new FrontierSemanticInterpreter(
                new LlmProviderRouter(List.of(google)), provider -> "semantic-test",
                List.of(LlmProvider.GOOGLE), new ObjectMapper());
        ExecutionObjectiveHandoff handoff = new ExecutionObjectiveHandoff() {
            @Override
            public HandoffReceipt submit(String humanId, String organizationContextId, String caseId,
                                         String conversationId, String externalMessageReference,
                                         String channel, NormalizedRequest request) {
                throw new AssertionError("generic Head handoff must not be used");
            }

            @Override
            public HandoffReceipt submitToWorker(String ownerWorkerId, String humanId,
                                                  String organizationContextId, String caseId,
                                                  String conversationId, String externalMessageReference,
                                                  String channel, NormalizedRequest request) {
                assertEquals("WORKER-GATEWAY-DIRECTOR", ownerWorkerId);
                assertEquals("organization:metatron", organizationContextId);
                assertEquals("workplace", channel);
                return new HandoffReceipt(true, "objective:workplace:worker", ownerWorkerId,
                        "queue:workplace:worker", "ACCEPTED", "ACCEPTED",
                        "OBJECTIVE_ACCEPTED_FOR_AUTONOMOUS_MANAGEMENT");
            }
        };
        WorkerInstructionAdmissionService admission = new WorkerInstructionAdmissionService(semantic, handoff);
        WorkerConversationGateway forbiddenGateway = (workerId, role, userMessage, context) -> {
            throw new AssertionError("execution instruction must not fall through to conversational cognition");
        };
        DirectWorkerConversationService service = new DirectWorkerConversationService(
                fixture.bindings(), fixture.memory(), fixture.controlRoom(), fixture.directory(),
                forbiddenGateway, admission);

        var reply = service.converseOrAdmit(
                "human-primary", "WORKER-GATEWAY-DIRECTOR",
                "Audit Gateway now and return the result.", "workplace",
                "workplace:worker-chat:test-1");

        assertEquals("WORKER-GATEWAY-DIRECTOR", reply.workerId());
        assertTrue(reply.turn().worker().contains("objective:workplace:worker"));
        assertTrue(reply.turn().evidenceReferences().contains("worker-objective-owner:WORKER-GATEWAY-DIRECTOR"));
        assertEquals(1, fixture.memory().turnCount("human-primary", "WORKER-GATEWAY-DIRECTOR"));
    }

    @Test
    void directWorkerBindingDoesNotHijackGenericChatWhenNoWorkerIsSelected() {
        Fixture fixture = fixture();
        DirectWorkerConversationService service = new DirectWorkerConversationService(
                fixture.bindings(), fixture.memory(), fixture.controlRoom(), fixture.directory(),
                (workerId, role, userMessage, context) ->
                        new WorkerConversationGateway.Reply("unused", "unused", List.of()));

        assertTrue(service.handle(interaction("Metatron", "msg-generic")).isEmpty());
        assertTrue(service.handle(interaction("What is the current time?", "msg-generic-2")).isEmpty());
    }

    @Test
    void chatAndWorkControlsExitDirectWorkerAndFallThroughToNormalProductRouting() {
        Fixture fixture = fixture();
        DirectWorkerConversationService service = new DirectWorkerConversationService(
                fixture.bindings(), fixture.memory(), fixture.controlRoom(), fixture.directory(),
                (workerId, role, userMessage, context) ->
                        new WorkerConversationGateway.Reply("unused", "unused", List.of()));

        assertTrue(service.handle(interaction("/worker WORKER-GATEWAY-DIRECTOR", "msg-1")).isPresent());
        assertEquals(Optional.of("WORKER-GATEWAY-DIRECTOR"),
                service.activeWorkerId("conversation:human:human-primary"));

        assertTrue(service.handle(interaction(ConversationSurfaceModeService.CHAT_CONTROL, "msg-2")).isEmpty());
        assertTrue(service.activeWorkerId("conversation:human:human-primary").isEmpty());
    }

    private MetatronInteraction interaction(String text, String messageRef) {
        return new MetatronInteraction(
                new ActorRef("human-primary", ActorRef.ActorType.HUMAN),
                new ActorRef("metatron-workforce", ActorRef.ActorType.WORKER),
                "organization:metatron",
                "conversation:human:human-primary",
                "telegram",
                "telegram:user:1",
                "telegram:chat:1",
                messageRef,
                text);
    }

    private Fixture fixture() {
        WorkforceCoreService core = new WorkforceCoreService();
        core.recognizeParticipant("P-GATEWAY", WorkforceCoreService.ParticipantType.AI, "test");
        core.admitWorker("WORKER-GATEWAY-DIRECTOR", "P-GATEWAY");
        core.participate(
                "PART-GATEWAY",
                "WORKER-GATEWAY-DIRECTOR",
                "organization:metatron",
                "position:gateway-director",
                "ROLE-HEAD-OF-GATEWAY");
        core.attestCapability(
                "WORKER-GATEWAY-DIRECTOR",
                "gateway.audit.read",
                1.0,
                "evidence:test");
        core.setAvailability("WORKER-GATEWAY-DIRECTOR", true, 1.0);

        ManagementAutonomyService management = new ManagementAutonomyService();
        WorkplaceDashboardService dashboard =
                new WorkplaceDashboardService(core, management, new WorkService());

        WorkerRuntimeProfileBindingService profiles = WorkerRuntimeProfileBindingService.inMemory();
        profiles.bind(
                "WORKER-GATEWAY-DIRECTOR",
                WorkerRuntimeProfileBindingService.GENERAL_ENGINEERING_PROFILE,
                "workforce.staffing.gateway-director",
                Instant.now());

        RuntimeRegistry runtimeRegistry = new RuntimeRegistry();
        RuntimeCapacityCoordinator runtimeCapacity = new RuntimeCapacityCoordinator(runtimeRegistry);
        WorkplaceControlRoomService controlRoom = new WorkplaceControlRoomService(
                dashboard, core, profiles, runtimeRegistry, runtimeCapacity);
        MeetingWorkerDirectory directory = new MeetingWorkerDirectory(core, runtimeRegistry);

        PersistentWorkerConversationMemoryStore memory =
                new PersistentWorkerConversationMemoryStore(
                        new ObjectMapper(),
                        temp.resolve("memory").toString(),
                        temp.resolve("legacy").toString());
        DirectWorkerConversationBindingStore bindings =
                new DirectWorkerConversationBindingStore(
                        new ObjectMapper(), temp.resolve("bindings.json"));

        return new Fixture(controlRoom, directory, memory, bindings);
    }

    private record Fixture(
            WorkplaceControlRoomService controlRoom,
            MeetingWorkerDirectory directory,
            PersistentWorkerConversationMemoryStore memory,
            DirectWorkerConversationBindingStore bindings) {}
}
