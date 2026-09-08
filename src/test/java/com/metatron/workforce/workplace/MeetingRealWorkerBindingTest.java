package com.metatron.workforce.workplace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.interaction.MetatronInteraction;
import com.metatron.workforce.interaction.intelligence.ExecutionObjectiveHandoff;
import com.metatron.workforce.phase3.ActorRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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
    void missingRealWorkerFailsClosedInsteadOfSimulatingRole() {
        WorkforceCoreService core = new WorkforceCoreService();
        WorkplaceMeetingService service = new WorkplaceMeetingService(
                new PersistentMeetingStore(temp.resolve("missing"), new ObjectMapper()),
                new MeetingRoleDeliberator() {
                    @Override public Deliberation deliberate(String role, String purpose, String context) {
                        fail("must not simulate missing worker");
                        return new Deliberation("never", "");
                    }
                    @Override public Deliberation synthesize(String purpose, List<MeetingRecord.Contribution> contributions, String context) {
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

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> service.handle(interaction, ""));
        assertTrue(failure.getMessage().startsWith("meeting_worker_not_found:"));
    }
}
