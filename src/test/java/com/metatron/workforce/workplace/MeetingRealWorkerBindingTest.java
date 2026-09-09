package com.metatron.workforce.workplace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.interaction.MetatronInteraction;
import com.metatron.workforce.interaction.intelligence.ExecutionObjectiveHandoff;
import com.metatron.workforce.interaction.intelligence.WorkerIntelligenceService;
import com.metatron.workforce.management.GatewayDirectorAppointmentCapability;
import com.metatron.workforce.runtime.RuntimeCapacityCoordinator;
import com.metatron.workforce.runtime.RuntimeRegistry;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import com.metatron.workforce.phase3.ActorRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MeetingRealWorkerBindingTest {
    @TempDir Path temp;

    @Test
    void oneRoleMeetingBindsToCanonicalActiveWorkerIdAndUsesItAsCognitionRequester() {
        WorkforceCoreService core = new WorkforceCoreService();
        core.recognizeParticipant("participant:gateway-head", WorkforceCoreService.ParticipantType.AI, "test");
        core.admitWorker("worker:gateway-head:01", "participant:gateway-head");
        core.participate("participation:gateway-head", "worker:gateway-head:01",
                "organization:metatron", "position:head-of-gateway", "role:head-of-gateway");

        AtomicReference<String> requester = new AtomicReference<>();
        MeetingRoleDeliberator deliberator = new MeetingRoleDeliberator() {
            @Override public Deliberation deliberate(String role, String purpose, String context) {
                return new Deliberation("unused", "");
            }
            @Override public Deliberation converse(String workerId, String role, String message, String context) {
                requester.set(workerId);
                return new Deliberation("Có tao đây.", "provider:test");
            }
            @Override public Deliberation synthesize(String purpose, List<MeetingRecord.Contribution> contributions, String context) {
                return new Deliberation("unused", "");
            }
        };

        PersistentMeetingStore store = new PersistentMeetingStore(temp.resolve("meetings"), new ObjectMapper());
        WorkplaceMeetingService service = new WorkplaceMeetingService(
                store, deliberator, ExecutionObjectiveHandoff.unavailable(), new MeetingWorkerDirectory(core));

        MetatronInteraction interaction = new MetatronInteraction(
                new ActorRef("founder", ActorRef.ActorType.HUMAN),
                new ActorRef("workforce-head", ActorRef.ActorType.WORKER),
                "organization:metatron", "conversation:gateway", "telegram",
                "telegram:user:1", "telegram:chat:1", "telegram:update:1",
                "Cho tao trò chuyện với Head of Gateway");

        String response = service.handle(interaction, "");
        assertEquals("worker:gateway-head:01", requester.get());
        assertTrue(response.contains("Có tao đây."));

        MeetingRecord meeting = service.findByExternalMessageReference("telegram:update:1").orElseThrow();
        assertEquals(List.of("human:founder", "worker:gateway-head:01"), meeting.participants());
        assertEquals("worker:gateway-head:01", meeting.contributions().getFirst().participant());
        assertTrue(meeting.evidenceRefs().stream().anyMatch(v ->
                v.equals("meeting-worker:worker:gateway-head:01:participation=participation:gateway-head")));
    }


    @Test
    void canonicalWorkerIdCanBeCalledDirectly() {
        WorkforceCoreService core = new WorkforceCoreService();
        core.recognizeParticipant("participant:gateway-head", WorkforceCoreService.ParticipantType.AI, "test");
        core.admitWorker("worker:gateway-head:01", "participant:gateway-head");
        core.participate("participation:gateway-head", "worker:gateway-head:01",
                "organization:metatron", "position:head-of-gateway", "role:head-of-gateway");

        AtomicReference<String> requester = new AtomicReference<>();
        MeetingRoleDeliberator deliberator = new MeetingRoleDeliberator() {
            @Override public Deliberation deliberate(String role, String purpose, String context) {
                return new Deliberation("unused", "");
            }
            @Override public Deliberation converse(String workerId, String role, String message, String context) {
                requester.set(workerId);
                return new Deliberation("Tao đây.", "provider:test");
            }
            @Override public Deliberation synthesize(String purpose, List<MeetingRecord.Contribution> contributions, String context) {
                return new Deliberation("unused", "");
            }
        };
        WorkplaceMeetingService service = new WorkplaceMeetingService(
                new PersistentMeetingStore(temp.resolve("direct"), new ObjectMapper()),
                deliberator, ExecutionObjectiveHandoff.unavailable(), new MeetingWorkerDirectory(core));

        assertTrue(service.supportsInMeetingMode("worker:gateway-head:01"));
        MetatronInteraction interaction = new MetatronInteraction(
                new ActorRef("founder", ActorRef.ActorType.HUMAN),
                new ActorRef("workforce-head", ActorRef.ActorType.WORKER),
                "organization:metatron", "conversation:direct", "telegram",
                "telegram:user:1", "telegram:chat:1", "telegram:update:direct",
                "worker:gateway-head:01");

        String response = service.handle(interaction, "");
        assertEquals("worker:gateway-head:01", requester.get());
        assertTrue(response.contains("Tao đây."));
    }


    @Test
    void canonicalUppercaseWorkerIdCanBeCalledDirectly() {
        WorkforceCoreService core = new WorkforceCoreService();
        core.recognizeParticipant("participant:gateway-director-ai", WorkforceCoreService.ParticipantType.AI, "test");
        core.admitWorker("WORKER-GATEWAY-DIRECTOR", "participant:gateway-director-ai");
        core.participate("participation:gateway-director:metatron", "WORKER-GATEWAY-DIRECTOR",
                "organization:metatron", "position:gateway-director", "ROLE-HEAD-OF-GATEWAY");

        AtomicReference<String> requester = new AtomicReference<>();
        MeetingRoleDeliberator deliberator = new MeetingRoleDeliberator() {
            @Override public Deliberation deliberate(String role, String purpose, String context) {
                return new Deliberation("unused", "");
            }
            @Override public Deliberation converse(String workerId, String role, String message, String context) {
                requester.set(workerId);
                return new Deliberation("Tao đây.", "provider:test");
            }
            @Override public Deliberation synthesize(String purpose, List<MeetingRecord.Contribution> contributions, String context) {
                return new Deliberation("unused", "");
            }
        };
        WorkplaceMeetingService service = new WorkplaceMeetingService(
                new PersistentMeetingStore(temp.resolve("uppercase-direct"), new ObjectMapper()),
                deliberator, ExecutionObjectiveHandoff.unavailable(), new MeetingWorkerDirectory(core));

        assertTrue(service.supportsInMeetingMode("WORKER-GATEWAY-DIRECTOR"));
        assertTrue(service.supports("WORKER-GATEWAY-DIRECTOR"));

        MetatronInteraction interaction = new MetatronInteraction(
                new ActorRef("founder", ActorRef.ActorType.HUMAN),
                new ActorRef("workforce-head", ActorRef.ActorType.WORKER),
                "organization:metatron", "conversation:direct-uppercase", "telegram",
                "telegram:user:1", "telegram:chat:1", "telegram:update:direct-uppercase",
                "WORKER-GATEWAY-DIRECTOR");

        String response = service.handle(interaction, "");
        assertEquals("WORKER-GATEWAY-DIRECTOR", requester.get());
        assertTrue(response.contains("Tao đây."));
        assertEquals("WORKER-GATEWAY-DIRECTOR",
                service.findByExternalMessageReference("telegram:update:direct-uppercase")
                        .orElseThrow().participants().get(1));
    }

    @Test
    void multipleCanonicalWorkerIdsOpenOneLiveRoomAndCallEachExactWorker() {
        WorkforceCoreService core = new WorkforceCoreService();
        core.recognizeParticipant("participant:gateway-director-ai", WorkforceCoreService.ParticipantType.AI, "test");
        core.admitWorker("WORKER-GATEWAY-DIRECTOR", "participant:gateway-director-ai");
        core.participate("participation:gateway-director:metatron", "WORKER-GATEWAY-DIRECTOR",
                "organization:metatron", "position:gateway-director", "ROLE-HEAD-OF-GATEWAY");

        core.recognizeParticipant("participant:operations-ai", WorkforceCoreService.ParticipantType.AI, "test");
        core.admitWorker("WORKER-OPERATIONS", "participant:operations-ai");
        core.participate("participation:operations:metatron", "WORKER-OPERATIONS",
                "organization:metatron", "position:head-of-operations", "role:head-of-operations");

        java.util.ArrayList<String> called = new java.util.ArrayList<>();
        WorkerConversationGateway workerConversation = (workerId, role, message, context) -> {
            called.add(workerId);
            return new WorkerConversationGateway.Reply(
                    "reply:" + workerId,
                    "worker-cognition:" + workerId,
                    List.of("evidence:" + workerId));
        };
        WorkplaceMeetingService service = new WorkplaceMeetingService(
                new PersistentMeetingStore(temp.resolve("multi-direct"), new ObjectMapper()),
                new MeetingRoleDeliberator() {
                    @Override public Deliberation deliberate(String role, String purpose, String context) {
                        fail("shadow role deliberation must not run");
                        return new Deliberation("never", "");
                    }
                    @Override public Deliberation synthesize(String purpose, List<MeetingRecord.Contribution> contributions, String context) {
                        fail("shadow synthesis must not run");
                        return new Deliberation("never", "");
                    }
                },
                ExecutionObjectiveHandoff.unavailable(),
                new MeetingWorkerDirectory(core),
                workerConversation);

        assertEquals(List.of("WORKER-GATEWAY-DIRECTOR", "WORKER-OPERATIONS"),
                WorkplaceMeetingService.requestedWorkerIds(
                        "WORKER-GATEWAY-DIRECTOR and WORKER-OPERATIONS"));

        MetatronInteraction open = new MetatronInteraction(
                new ActorRef("founder", ActorRef.ActorType.HUMAN),
                new ActorRef("workforce-head", ActorRef.ActorType.WORKER),
                "organization:metatron", "conversation:multi-direct", "telegram",
                "telegram:user:1", "telegram:chat:1", "telegram:update:multi-direct",
                "WORKER-GATEWAY-DIRECTOR and WORKER-OPERATIONS");

        String response = service.handle(open, "");
        assertEquals(List.of("WORKER-GATEWAY-DIRECTOR", "WORKER-OPERATIONS"), called);
        assertTrue(response.contains("WORKER-GATEWAY-DIRECTOR"));
        assertTrue(response.contains("WORKER-OPERATIONS"));

        MeetingRecord room = service.findByExternalMessageReference("telegram:update:multi-direct").orElseThrow();
        assertEquals(List.of("human:founder", "WORKER-GATEWAY-DIRECTOR", "WORKER-OPERATIONS"),
                room.participants());
        assertEquals(2, room.contributions().size());
        assertEquals(MeetingRecord.Status.ACTIVE, room.status());
    }

    @Test
    void activeWorkerDirectoryOnlyListsWorkersWithRunningRuntimeAndShowsRuntimeId() {
        WorkforceCoreService core = new WorkforceCoreService();
        core.recognizeParticipant("participant:gateway-live", WorkforceCoreService.ParticipantType.AI, "test");
        core.admitWorker("WORKER-GATEWAY-LIVE", "participant:gateway-live");
        core.participate("participation:gateway-live", "WORKER-GATEWAY-LIVE",
                "organization:metatron", "position:gateway-director", "ROLE-HEAD-OF-GATEWAY");

        RuntimeRegistry registry = new RuntimeRegistry();
        MeetingWorkerDirectory directory = new MeetingWorkerDirectory(core, registry);
        assertTrue(directory.listActive().isEmpty(),
                "Core ACTIVE alone must not be advertised as a live Worker");

        RuntimeCapacityCoordinator capacity = new RuntimeCapacityCoordinator(registry);
        var runtime = capacity.ensureRunning("WORKER-GATEWAY-LIVE");

        var live = directory.listActive();
        assertEquals(1, live.size());
        assertEquals("WORKER-GATEWAY-LIVE", live.getFirst().workerId());
        assertEquals(runtime.runtimeId(), live.getFirst().runtimeId());
        assertEquals("RUNNING", live.getFirst().runtimeState());

        WorkplaceMeetingService service = new WorkplaceMeetingService(
                new PersistentMeetingStore(temp.resolve("live-directory-output"), new ObjectMapper()),
                new MeetingRoleDeliberator() {
                    @Override public Deliberation deliberate(String role, String purpose, String context) {
                        fail("directory read must not invoke shadow deliberation");
                        return new Deliberation("never", "");
                    }
                    @Override public Deliberation synthesize(String purpose, List<MeetingRecord.Contribution> contributions, String context) {
                        fail("directory read must not invoke synthesis");
                        return new Deliberation("never", "");
                    }
                },
                ExecutionObjectiveHandoff.unavailable(),
                directory,
                (workerId, role, message, context) -> {
                    throw new AssertionError("directory read must not invoke Worker cognition");
                });

        MetatronInteraction directoryRequest = new MetatronInteraction(
                new ActorRef("founder", ActorRef.ActorType.HUMAN),
                new ActorRef("workforce-head", ActorRef.ActorType.WORKER),
                "organization:metatron", "conversation:live-directory", "telegram",
                "telegram:user:1", "telegram:chat:1", "telegram:update:live-directory",
                "active workers");
        String rendered = service.handle(directoryRequest, "");
        assertTrue(rendered.contains("worker_id=`WORKER-GATEWAY-LIVE`"));
        assertTrue(rendered.contains("runtime_id=`" + runtime.runtimeId() + "`"));
        assertTrue(rendered.contains("runtime_state=RUNNING"));
    }

    @Test
    void canonicalWorkerConversationRequiresRuntimeBindingAndUsesWorkerCognitionBoundary() {
        WorkforceCoreService core = new WorkforceCoreService();
        core.recognizeParticipant("participant:gateway-director-ai", WorkforceCoreService.ParticipantType.AI, "test");
        core.admitWorker(GatewayDirectorAppointmentCapability.WORKER_ID, "participant:gateway-director-ai");
        core.participate("participation:gateway-director:metatron", GatewayDirectorAppointmentCapability.WORKER_ID,
                "organization:metatron", GatewayDirectorAppointmentCapability.POSITION_REF,
                GatewayDirectorAppointmentCapability.ROLE_REF);
        core.attestCapability(GatewayDirectorAppointmentCapability.WORKER_ID,
                GatewayDirectorAppointmentCapability.GATEWAY_AUDIT_CAPABILITY, 1.0, "evidence:test");
        core.setAvailability(GatewayDirectorAppointmentCapability.WORKER_ID, true, 1.0);

        WorkerRuntimeProfileBindingService runtimeProfiles = WorkerRuntimeProfileBindingService.inMemory();
        AtomicReference<WorkerIntelligenceService.Request> cognition = new AtomicReference<>();
        WorkerIntelligenceService workerIntelligence = request -> {
            cognition.set(request);
            return new WorkerIntelligenceService.Response(
                    "worker-cognition-test",
                    "Có tao đây.",
                    List.of("worker-intelligence-provider:test"));
        };
        RuntimeCapacityCoordinator runtimeCapacity = new RuntimeCapacityCoordinator(new RuntimeRegistry());
        CanonicalWorkerConversationService service =
                new CanonicalWorkerConversationService(core, runtimeProfiles, runtimeCapacity, workerIntelligence);

        IllegalStateException unbound = assertThrows(IllegalStateException.class, () ->
                service.converse(GatewayDirectorAppointmentCapability.WORKER_ID,
                        "Head of Gateway", "Mày ở đây không?", ""));
        assertTrue(unbound.getMessage().contains("worker-runtime-profile-unbound"));

        runtimeProfiles.bind(
                GatewayDirectorAppointmentCapability.WORKER_ID,
                WorkerRuntimeProfileBindingService.GENERAL_ENGINEERING_PROFILE,
                GatewayDirectorAppointmentCapability.CAPABILITY,
                Instant.parse("2026-09-08T00:00:00Z"));

        WorkerConversationGateway.Reply reply = service.converse(
                GatewayDirectorAppointmentCapability.WORKER_ID,
                "Head of Gateway",
                "Mày ở đây không?",
                "recent meeting turn");

        assertEquals("Có tao đây.", reply.text());
        assertEquals(GatewayDirectorAppointmentCapability.WORKER_ID, cognition.get().requester());
        assertEquals("worker.live.conversation", cognition.get().capability());
        assertTrue(cognition.get().context().contains("worker_id=WORKER-GATEWAY-DIRECTOR"));
        assertTrue(cognition.get().context().contains(
                "runtime_profile=" + WorkerRuntimeProfileBindingService.GENERAL_ENGINEERING_PROFILE));
        assertTrue(reply.evidenceReferences().contains("worker-intelligence-provider:test"));
        assertTrue(reply.evidenceReferences().contains("worker-conversation-request:worker-cognition-test"));
        assertFalse(reply.runtimeId().isBlank());
        assertTrue(cognition.get().context().contains("runtime_id=" + reply.runtimeId()));
        assertTrue(cognition.get().context().contains("runtime_state=RUNNING"));
    }

    @Test
    void staleRoleBoundMeetingReconcilesToCanonicalWorkerInsteadOfFailing() {
        WorkforceCoreService core = new WorkforceCoreService();
        core.recognizeParticipant("participant:gateway-director-ai", WorkforceCoreService.ParticipantType.AI, "test");
        core.admitWorker("WORKER-GATEWAY-DIRECTOR", "participant:gateway-director-ai");
        core.participate("participation:gateway-director:metatron", "WORKER-GATEWAY-DIRECTOR",
                "organization:metatron", "position:gateway-director", "ROLE-HEAD-OF-GATEWAY");

        AtomicReference<String> requester = new AtomicReference<>();
        MeetingRoleDeliberator deliberator = new MeetingRoleDeliberator() {
            @Override public Deliberation deliberate(String role, String purpose, String context) {
                return new Deliberation("unused", "");
            }
            @Override public Deliberation converse(String workerId, String role, String message, String context) {
                requester.set(workerId);
                return new Deliberation("Tao đây.", "provider:test");
            }
            @Override public Deliberation synthesize(String purpose, List<MeetingRecord.Contribution> contributions, String context) {
                return new Deliberation("unused", "");
            }
        };

        PersistentMeetingStore store = new PersistentMeetingStore(temp.resolve("stale-rebind"), new ObjectMapper());
        MeetingRecord stale = new MeetingRecord(
                "meeting:stale", "organization:metatron", "conversation:stale",
                "telegram", "telegram:update:old", "Conversation with Head of Gateway",
                "Head of Gateway", "human:founder",
                List.of("human:founder", "role:head-of-gateway"),
                List.of("Live conversation"),
                List.of(new MeetingRecord.Contribution("role:head-of-gateway", "Head of Gateway",
                        "old reply", "provider:old")),
                "", List.of(), List.of(), List.of(), List.of(
                        MeetingRecord.Status.PROPOSED.name(),
                        MeetingRecord.Status.OPEN.name(),
                        MeetingRecord.Status.ACTIVE.name()),
                MeetingRecord.Status.ACTIVE, "2026-09-08T00:00:00Z", "", false);
        store.save(stale);

        WorkplaceMeetingService service = new WorkplaceMeetingService(
                store, deliberator, ExecutionObjectiveHandoff.unavailable(), new MeetingWorkerDirectory(core));

        MetatronInteraction next = new MetatronInteraction(
                new ActorRef("founder", ActorRef.ActorType.HUMAN),
                new ActorRef("workforce-head", ActorRef.ActorType.WORKER),
                "organization:metatron", "conversation:stale", "telegram",
                "telegram:user:1", "telegram:chat:1", "telegram:update:new",
                "Mày đang ở đây không?");

        String response = service.handle(next, "");
        assertTrue(response.contains("Tao đây."));
        assertEquals("WORKER-GATEWAY-DIRECTOR", requester.get());
        MeetingRecord rebound = service.require("meeting:stale");
        assertEquals(List.of("human:founder", "WORKER-GATEWAY-DIRECTOR"), rebound.participants());
    }

    @Test
    void duplicateGatewayHeadWorkersResolveCanonicalForRoleRefAlias() {
        WorkforceCoreService core = new WorkforceCoreService();
        core.recognizeParticipant("participant:gateway-director-ai", WorkforceCoreService.ParticipantType.AI, "test");
        core.admitWorker("WORKER-GATEWAY-DIRECTOR", "participant:gateway-director-ai");
        core.participate("participation:gateway-director:metatron", "WORKER-GATEWAY-DIRECTOR",
                "organization:metatron", "position:gateway-director", "ROLE-HEAD-OF-GATEWAY");

        core.recognizeParticipant("participant:gateway-head-legacy", WorkforceCoreService.ParticipantType.AI, "test");
        core.admitWorker("WORKER-GATEWAY-HEAD-01", "participant:gateway-head-legacy");
        core.participate("participation:gateway-head-legacy", "WORKER-GATEWAY-HEAD-01",
                "organization:metatron", "POSITION-HEAD-OF-GATEWAY", "ROLE-HEAD-OF-GATEWAY");

        MeetingWorkerDirectory directory = new MeetingWorkerDirectory(core);
        assertEquals("WORKER-GATEWAY-DIRECTOR",
                directory.resolveActive("Head of Gateway").workerId());
        assertEquals("WORKER-GATEWAY-DIRECTOR",
                directory.resolveActive("ROLE HEAD OF GATEWAY").workerId());
        assertEquals("WORKER-GATEWAY-DIRECTOR",
                directory.resolveActive("ROLE-HEAD-OF-GATEWAY").workerId());
    }

    @Test
    void naturalGatewayDirectorPhrasesResolveToCanonicalHeadRole() {
        assertEquals(List.of("Head of Gateway"),
                WorkplaceMeetingService.requestedRoles("Meeting, call for me director of gateway"));
        assertEquals(List.of("Head of Gateway"),
                WorkplaceMeetingService.requestedRoles("I want to have a meeting with director of gateway"));
        assertEquals(List.of("Head of Gateway"),
                WorkplaceMeetingService.requestedRoles("meeting with gateway director"));
    }

    @Test
    void missingRealWorkerReturnsExplicitUnavailableInsteadOfSimulatingRoleOrDeadLettering() {
        WorkforceCoreService core = new WorkforceCoreService();
        AtomicReference<Boolean> shadowCalled = new AtomicReference<>(false);
        WorkplaceMeetingService service = new WorkplaceMeetingService(
                new PersistentMeetingStore(temp.resolve("missing"), new ObjectMapper()),
                new MeetingRoleDeliberator() {
                    @Override public Deliberation deliberate(String role, String purpose, String context) {
                        shadowCalled.set(true);
                        return new Deliberation("never", "");
                    }
                    @Override public Deliberation synthesize(String purpose, List<MeetingRecord.Contribution> contributions, String context) {
                        shadowCalled.set(true);
                        return new Deliberation("never", "");
                    }
                },
                ExecutionObjectiveHandoff.unavailable(),
                new MeetingWorkerDirectory(core));

        MetatronInteraction interaction = new MetatronInteraction(
                new ActorRef("founder", ActorRef.ActorType.HUMAN),
                new ActorRef("workforce-head", ActorRef.ActorType.WORKER),
                "organization:metatron", "conversation:missing", "telegram",
                "telegram:user:1", "telegram:chat:1", "telegram:update:missing",
                "Head of Gateway");

        String response = service.handle(interaction, "");
        assertTrue(response.startsWith("🏛 **WORKER NOT AVAILABLE**"));
        assertTrue(response.contains("Head of Gateway"));
        assertTrue(response.contains("No role/persona simulation was used"));
        assertFalse(shadowCalled.get());
        assertTrue(service.list().isEmpty());
    }

    @Test
    void multiRoleMeetingFailsClosedIfAnyRequestedRoleHasNoLiveWorker() {
        WorkforceCoreService core = new WorkforceCoreService();
        core.recognizeParticipant("participant:gateway-director-ai", WorkforceCoreService.ParticipantType.AI, "test");
        core.admitWorker("WORKER-GATEWAY-DIRECTOR", "participant:gateway-director-ai");
        core.participate("participation:gateway-director:metatron", "WORKER-GATEWAY-DIRECTOR",
                "organization:metatron", "position:gateway-director", "ROLE-HEAD-OF-GATEWAY");

        AtomicReference<Boolean> cognitionCalled = new AtomicReference<>(false);
        WorkerConversationGateway workerConversation = (workerId, role, message, context) -> {
            cognitionCalled.set(true);
            return new WorkerConversationGateway.Reply("never", "never", List.of());
        };
        WorkplaceMeetingService service = new WorkplaceMeetingService(
                new PersistentMeetingStore(temp.resolve("partial-multi"), new ObjectMapper()),
                new MeetingRoleDeliberator() {
                    @Override public Deliberation deliberate(String role, String purpose, String context) {
                        fail("shadow deliberation must not run");
                        return new Deliberation("never", "");
                    }
                    @Override public Deliberation synthesize(String purpose, List<MeetingRecord.Contribution> contributions, String context) {
                        fail("shadow synthesis must not run");
                        return new Deliberation("never", "");
                    }
                },
                ExecutionObjectiveHandoff.unavailable(),
                new MeetingWorkerDirectory(core),
                workerConversation);

        MetatronInteraction interaction = new MetatronInteraction(
                new ActorRef("founder", ActorRef.ActorType.HUMAN),
                new ActorRef("workforce-head", ActorRef.ActorType.WORKER),
                "organization:metatron", "conversation:partial-multi", "telegram",
                "telegram:user:1", "telegram:chat:1", "telegram:update:partial-multi",
                "Meeting with Head of Gateway and Head of Finance");

        String response = service.handle(interaction, "");
        assertTrue(response.startsWith("🏛 **WORKER NOT AVAILABLE**"));
        assertTrue(response.contains("Head of Finance"));
        assertFalse(response.contains("METATRON MEETING COMPLETED"));
        assertFalse(cognitionCalled.get(), "must resolve the full requested roster before calling any worker");
        assertTrue(service.list().isEmpty());
    }
}
