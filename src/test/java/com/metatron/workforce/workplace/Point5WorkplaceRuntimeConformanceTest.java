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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class Point5WorkplaceRuntimeConformanceTest {
    @TempDir Path temp;

    @Test
    void multiRoleMeetingUsesOnlyRealWorkersAndStaysLive() {
        ObjectMapper json = new ObjectMapper();
        PersistentMeetingStore store = new PersistentMeetingStore(temp.resolve("meetings"), json);
        WorkforceCoreService core = new WorkforceCoreService();
        staff(core, "WORKER-STRATEGY", "role:head-of-strategy", "position:head-of-strategy");
        staff(core, "WORKER-FINANCE", "role:head-of-finance", "position:head-of-finance");
        staff(core, "WORKER-OPERATIONS", "role:head-of-operations", "position:head-of-operations");

        AtomicInteger shadowCalls = new AtomicInteger();
        MeetingRoleDeliberator forbiddenShadowPath = new MeetingRoleDeliberator() {
            @Override public Deliberation deliberate(String role, String purpose, String context) {
                shadowCalls.incrementAndGet();
                throw new AssertionError("multi-role Meeting must never fabricate a role contribution");
            }
            @Override public Deliberation synthesize(String purpose, List<MeetingRecord.Contribution> contributions, String context) {
                shadowCalls.incrementAndGet();
                throw new AssertionError("live Meeting must not auto-synthesize or close");
            }
        };
        WorkerConversationGateway workerConversation = (workerId, role, message, context) ->
                new WorkerConversationGateway.Reply(
                        "Live reply from " + workerId,
                        "worker-cognition:" + workerId + ":" + message.hashCode(),
                        List.of("worker-cognition-evidence:" + workerId));

        WorkplaceMeetingService service = new WorkplaceMeetingService(
                store, forbiddenShadowPath, ExecutionObjectiveHandoff.unavailable(),
                new MeetingWorkerDirectory(core), workerConversation);

        String request = "Gọi Head of Strategy, Head of Finance và Head of Operations vào bàn kế hoạch mở thị trường.";
        assertTrue(service.supports(request));
        assertFalse(service.supports("Giá Bitcoin hôm nay là bao nhiêu?"));
        assertFalse(service.supports("Take ownership of one Objective: audit kelvinka38/bios and deliver verified evidence."));

        MetatronInteraction interaction = interaction(
                "conversation:founder:1", "telegram:update:point5-1", request);

        String response = service.handle(interaction, "prior conversation context");
        assertEquals(0, shadowCalls.get());
        assertTrue(response.contains("WORKER-STRATEGY"));
        assertTrue(response.contains("WORKER-FINANCE"));
        assertTrue(response.contains("WORKER-OPERATIONS"));
        assertFalse(response.contains("METATRON MEETING COMPLETED"));
        assertFalse(response.contains("MEETING SYNTHESIS"));
        assertFalse(response.contains("Follow-up:"));

        MeetingRecord meeting = service.findByExternalMessageReference("telegram:update:point5-1").orElseThrow();
        assertEquals(MeetingRecord.Status.ACTIVE, meeting.status());
        assertEquals(List.of("PROPOSED", "OPEN", "ACTIVE"), meeting.lifecycle());
        assertEquals(List.of(
                "human:founder",
                "WORKER-STRATEGY",
                "WORKER-FINANCE",
                "WORKER-OPERATIONS"), meeting.participants());
        assertEquals(3, meeting.contributions().size());
        assertEquals(List.of("WORKER-STRATEGY", "WORKER-FINANCE", "WORKER-OPERATIONS"),
                meeting.contributions().stream().map(MeetingRecord.Contribution::participant).toList());
        assertTrue(meeting.recommendation().isBlank());
        assertTrue(meeting.actionItems().isEmpty());
        assertTrue(meeting.decisionRefs().isEmpty());
        assertFalse(meeting.authorityCreated());
        assertTrue(meeting.evidenceRefs().contains(
                "meeting-worker:WORKER-STRATEGY:participation=participation:worker-strategy"));
        assertTrue(meeting.evidenceRefs().contains(
                "meeting-worker:WORKER-FINANCE:participation=participation:worker-finance"));
        assertTrue(meeting.evidenceRefs().contains(
                "meeting-worker:WORKER-OPERATIONS:participation=participation:worker-operations"));

        String second = service.handle(interaction(
                "conversation:founder:1",
                "telegram:update:point5-2",
                "Ba đứa thấy rủi ro lớn nhất là gì?"), "recent live room context");
        assertTrue(second.contains("WORKER-STRATEGY"));
        assertTrue(second.contains("WORKER-FINANCE"));
        assertTrue(second.contains("WORKER-OPERATIONS"));
        assertEquals(6, service.require(meeting.meetingId()).contributions().size());
        assertEquals(0, shadowCalls.get());

        MeetingRecord reloaded = new PersistentMeetingStore(temp.resolve("meetings"), json)
                .find(meeting.meetingId()).orElseThrow();
        assertEquals(MeetingRecord.Status.ACTIVE, reloaded.status());
        assertEquals(meeting.participants(), reloaded.participants());
        assertEquals(6, reloaded.contributions().size());
    }

    @Test
    void explicitMeetingFollowUpSeedsWorkforceWhileOrdinaryConversationDoesNot() {
        ObjectMapper json = new ObjectMapper();
        PersistentMeetingStore store = new PersistentMeetingStore(temp.resolve("handoff"), json);
        WorkforceCoreService core = new WorkforceCoreService();
        staff(core, "WORKER-TECHNOLOGY", "role:head-of-technology", "position:head-of-technology");
        staff(core, "WORKER-OPERATIONS", "role:head-of-operations", "position:head-of-operations");

        AtomicInteger submissions = new AtomicInteger();
        ExecutionObjectiveHandoff handoff = (humanId, organizationContextId, caseId, conversationId,
                                             externalMessageReference, channel, request) -> {
            submissions.incrementAndGet();
            assertTrue(caseId.startsWith("meeting-case:meeting:"));
            assertTrue(request.objective().contains("meeting-derived objective"));
            return new ExecutionObjectiveHandoff.HandoffReceipt(
                    true, "objective:meeting-1", "worker:head", "queue:meeting-1",
                    "ACCEPTED", "ADMITTED", "meeting-follow-up");
        };
        MeetingRoleDeliberator forbiddenShadowPath = new MeetingRoleDeliberator() {
            @Override public Deliberation deliberate(String role, String purpose, String context) {
                throw new AssertionError("role simulation must not run");
            }
            @Override public Deliberation synthesize(String purpose, List<MeetingRecord.Contribution> contributions, String context) {
                throw new AssertionError("automatic synthesis must not run");
            }
        };
        WorkerConversationGateway workerConversation = (workerId, role, message, context) ->
                new WorkerConversationGateway.Reply(
                        "Live reply from " + workerId,
                        "worker-cognition:" + workerId,
                        List.of("worker-cognition-evidence:" + workerId));

        WorkplaceMeetingService service = new WorkplaceMeetingService(
                store, forbiddenShadowPath, handoff, new MeetingWorkerDirectory(core), workerConversation);

        String meetingRequest = "Mời Head of Technology và Head of Operations họp về Telegram routing.";
        service.handle(interaction(
                "conversation:founder:handoff",
                "telegram:update:meeting-create",
                meetingRequest), "");

        MeetingRecord meeting = service.findByExternalMessageReference("telegram:update:meeting-create").orElseThrow();
        assertEquals(MeetingRecord.Status.ACTIVE, meeting.status());
        assertEquals(List.of("human:founder", "WORKER-OPERATIONS", "WORKER-TECHNOLOGY").stream().sorted().toList(),
                meeting.participants().stream().sorted().toList());

        String followUp = "Triển khai " + meeting.followUpReference() + " cho Workforce thực hiện.";
        assertTrue(service.supports(followUp));
        assertFalse(service.supports(meeting.followUpReference()));
        assertFalse(service.supports("Fix Telegram routing now"));
        assertEquals(0, submissions.get());

        String response = service.handle(interaction(
                "conversation:founder:handoff",
                "telegram:update:meeting-follow-up",
                followUp), "");

        assertEquals(1, submissions.get());
        assertTrue(response.startsWith("METATRON MEETING WORK ACCEPTED"));
        assertTrue(response.contains("objective_id=objective:meeting-1"));
        assertTrue(response.contains("authority_source=explicit-human-meeting-follow-up"));

        MeetingRecord updated = service.require(meeting.meetingId());
        assertTrue(updated.decisionRefs().contains("objective:meeting-1"));
        assertTrue(updated.evidenceRefs().stream().anyMatch(v ->
                v.contains("meeting-work-handoff:" + meeting.followUpReference())
                        && v.contains("human-authorized=true")
                        && v.contains("meeting-authority-created=false")));
        assertFalse(updated.authorityCreated());
    }

    @Test
    void singleRoleMeetingIsPersistentNaturalConversationWithoutMemoBoilerplate() {
        ObjectMapper json = new ObjectMapper();
        PersistentMeetingStore store = new PersistentMeetingStore(temp.resolve("conversation"), json);
        WorkforceCoreService core = new WorkforceCoreService();
        staff(core, "WORKER-GATEWAY", "role:head-of-gateway", "position:head-of-gateway");

        WorkerConversationGateway workerConversation = (workerId, role, message, context) -> {
            if (message.toLowerCase().contains("trò chuyện") || message.toLowerCase().contains("tro chuyen")) {
                return new WorkerConversationGateway.Reply(
                        "Có tao đây. Mày muốn bàn gì về Gateway?",
                        "worker-cognition:gateway:1",
                        List.of("worker-evidence:gateway:1"));
            }
            return new WorkerConversationGateway.Reply(
                    "Ừ, tao đang nghe. Vấn đề routing mày muốn đào sâu chỗ nào?",
                    "worker-cognition:gateway:2",
                    List.of("worker-evidence:gateway:2"));
        };
        MeetingRoleDeliberator forbiddenShadowPath = new MeetingRoleDeliberator() {
            @Override public Deliberation deliberate(String role, String purpose, String context) {
                throw new AssertionError("shadow role path must not run");
            }
            @Override public Deliberation synthesize(String purpose, List<MeetingRecord.Contribution> contributions, String context) {
                throw new AssertionError("auto synthesis must not run");
            }
        };
        WorkplaceMeetingService service = new WorkplaceMeetingService(
                store, forbiddenShadowPath, ExecutionObjectiveHandoff.unavailable(),
                new MeetingWorkerDirectory(core), workerConversation);

        String first = service.handle(interaction(
                "conversation:live:gateway",
                "telegram:update:live-1",
                "Cho tao trò chuyện với Head of Gateway"), "old unrelated bios audit context");
        assertTrue(first.startsWith("🏛 **Head of Gateway**"));
        assertTrue(first.contains("WORKER-GATEWAY"));
        assertTrue(first.contains("Có tao đây"));
        assertFalse(first.contains("COMMUNICATION INITIATION"));
        assertFalse(first.contains("GOVERNANCE OBJECTIVES"));
        assertFalse(first.contains("case-"));
        assertTrue(service.hasActiveConversationMeeting("conversation:live:gateway"));

        MeetingRecord active = service.findByExternalMessageReference("telegram:update:live-1").orElseThrow();
        assertEquals(MeetingRecord.Status.ACTIVE, active.status());
        assertEquals(List.of("human:founder", "WORKER-GATEWAY"), active.participants());
        assertTrue(active.recommendation().isBlank());
        assertTrue(active.actionItems().isEmpty());

        String second = service.handle(interaction(
                "conversation:live:gateway",
                "telegram:update:live-2",
                "Tao thấy Telegram routing vẫn ngu. Mày thấy root cause ở đâu?"),
                "recent live meeting context");
        assertTrue(second.startsWith("🏛 **Head of Gateway**"));
        assertTrue(second.contains("WORKER-GATEWAY"));
        assertTrue(second.contains("tao đang nghe"));
        MeetingRecord continued = service.require(active.meetingId());
        assertEquals(MeetingRecord.Status.ACTIVE, continued.status());
        assertEquals(2, continued.contributions().size());
        assertTrue(continued.evidenceRefs().contains("interaction:telegram:update:live-2"));
    }

    @Test
    void meetingIntentIsChannelIndependentAndRequiresExplicitRoles() {
        ObjectMapper json = new ObjectMapper();
        WorkplaceMeetingService service = new WorkplaceMeetingService(
                new PersistentMeetingStore(temp.resolve("other"), json),
                new MeetingRoleDeliberator() {
                    @Override public Deliberation deliberate(String role, String purpose, String context) {
                        return new Deliberation(role + " contribution", "provider:test");
                    }
                    @Override public Deliberation synthesize(String purpose, List<MeetingRecord.Contribution> contributions, String context) {
                        return new Deliberation("synthesis", "provider:test");
                    }
                });
        assertTrue(service.supports("Summon Head of Strategy and Head of Finance into a meeting about unit economics."));
        assertTrue(service.supports("Mời Strategy, Finance và Operations họp về P&L."));
        assertTrue(service.supportsInMeetingMode("Head of gateway"));
        assertEquals(List.of("Head of Gateway"), WorkplaceMeetingService.requestedRoles("Head of gateway"));
        assertTrue(service.supportsInMeetingMode("Head of gateway and me (human)"));
        assertTrue(service.supports("Meeting with Head of Gateway"));
        assertEquals(List.of("Head of Gateway"),
                WorkplaceMeetingService.requestedRoles(
                        "Meeting with Head of Gateway about finance strategy and operations"));
        assertEquals(List.of("Head of Strategy", "Head of Finance", "Head of Operations"),
                WorkplaceMeetingService.requestedRoles(
                        "Mời Strategy, Finance và Operations họp về P&L."));
        assertTrue(service.supportsInMeetingMode("Head of tech, create for me head of gateway"));
        assertTrue(WorkplaceMeetingService.requestedRoles("Head of tech, create for me head of gateway")
                .stream().anyMatch(role -> role.equals("Head of Gateway")));
        assertFalse(service.supports("Let's have a meeting sometime."));
        assertFalse(service.supports("Finance outlook this week?"));
        assertTrue(WorkplaceMeetingService.isWorkerDirectoryRequest("Worker active"));
        assertTrue(WorkplaceMeetingService.isWorkerDirectoryRequest("active worker"));
        assertTrue(WorkplaceMeetingService.isWorkerDirectoryRequest("workers active"));
        assertTrue(WorkplaceMeetingService.isWorkerDirectoryRequest("worker đang hoạt động"));
    }

    private static MetatronInteraction interaction(String conversationId, String externalRef, String text) {
        return new MetatronInteraction(
                new ActorRef("founder", ActorRef.ActorType.HUMAN),
                new ActorRef("workforce-head", ActorRef.ActorType.WORKER),
                "organization:metatron", conversationId, "telegram",
                "telegram:user:1", "telegram:chat:1", externalRef, text);
    }

    private static void staff(WorkforceCoreService core, String workerId, String roleRef, String positionRef) {
        String slug = workerId.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        String participantId = "participant:" + slug;
        core.recognizeParticipant(participantId, WorkforceCoreService.ParticipantType.AI, "test:" + workerId);
        core.admitWorker(workerId, participantId);
        core.participate("participation:" + slug, workerId,
                "organization:metatron", positionRef, roleRef);
    }
}
