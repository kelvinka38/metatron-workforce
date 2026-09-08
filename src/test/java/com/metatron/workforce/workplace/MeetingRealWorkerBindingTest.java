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
