package com.metatron.workforce.workplace;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    void multiRoleMeetingIsFirstClassDurableAndDoesNotCreateAuthority() {
        ObjectMapper json = new ObjectMapper();
        PersistentMeetingStore store = new PersistentMeetingStore(temp.resolve("meetings"), json);
        MeetingRoleDeliberator fake = new MeetingRoleDeliberator() {
            @Override public Deliberation deliberate(String role, String purpose, String context) {
                return new Deliberation("Assessment from " + role + "; risk and recommendation are explicit.",
                        "provider:test:role:" + role.replace(' ', '-'));
            }
            @Override public Deliberation synthesize(String purpose, List<MeetingRecord.Contribution> contributions, String context) {
                return new Deliberation("Shared ground exists; disagreements remain explicit; Founder review is recommended.",
                        "provider:test:synthesis");
            }
        };
        WorkplaceMeetingService service = new WorkplaceMeetingService(store, fake);

        String request = "Gọi Head of Strategy, Head of Finance và Head of Operations vào bàn kế hoạch mở thị trường rồi đưa recommendation.";
        assertTrue(service.supports(request));
        assertFalse(service.supports("Giá Bitcoin hôm nay là bao nhiêu?"));
        assertFalse(service.supports("Take ownership of one Objective: audit kelvinka38/bios and deliver verified evidence."));

        MetatronInteraction interaction = new MetatronInteraction(
                new ActorRef("founder", ActorRef.ActorType.HUMAN),
                new ActorRef("workforce-head", ActorRef.ActorType.WORKER),
                "organization:metatron", "conversation:founder:1", "telegram",
                "telegram:user:1", "telegram:chat:1", "telegram:update:point5-1", request);

        String response = service.handle(interaction, "prior conversation context");
        assertTrue(response.startsWith("METATRON MEETING COMPLETED"));
        assertTrue(response.contains("authority_created=false"));
        assertTrue(response.contains("follow_up_ref=meeting-follow-up:meeting:"));
        assertTrue(response.contains("[Head of Strategy]"));
        assertTrue(response.contains("[Head of Finance]"));
        assertTrue(response.contains("[Head of Operations]"));

        MeetingRecord meeting = service.findByExternalMessageReference("telegram:update:point5-1").orElseThrow();
        assertEquals(MeetingRecord.Status.FOLLOW_UP, meeting.status());
        assertEquals(List.of("PROPOSED", "OPEN", "ACTIVE", "DECISION_PENDING", "CLOSED", "FOLLOW_UP"), meeting.lifecycle());
        assertEquals(4, meeting.participants().size()); // Human organizer + three requested roles.
        assertEquals(3, meeting.contributions().size());
        assertEquals(3, meeting.contributions().stream().map(MeetingRecord.Contribution::participant).distinct().count());
        assertFalse(meeting.recommendation().isBlank());
        assertFalse(meeting.actionItems().isEmpty());
        assertTrue(meeting.actionItems().getFirst().contains("handoff_ref=" + meeting.followUpReference()));
        assertTrue(meeting.evidenceRefs().stream().anyMatch(v ->
                v.contains("meeting-handoff:" + meeting.followUpReference())
                        && v.contains("authority-created=false")));
        assertTrue(meeting.decisionRefs().isEmpty());
        assertFalse(meeting.authorityCreated());
        assertEquals("telegram", meeting.channelProvider());
        assertEquals("conversation:founder:1", meeting.conversationId());
        assertTrue(meeting.evidenceRefs().stream().anyMatch(v -> v.equals("interaction:telegram:update:point5-1")));

        MeetingRecord reloaded = new PersistentMeetingStore(temp.resolve("meetings"), json).find(meeting.meetingId()).orElseThrow();
        assertEquals(meeting.meetingId(), reloaded.meetingId());
        assertEquals(meeting.followUpReference(), reloaded.followUpReference());
        assertEquals(MeetingRecord.Status.FOLLOW_UP, reloaded.status());
        assertEquals(3, reloaded.contributions().size());
        assertFalse(reloaded.authorityCreated());
    }


    @Test
    void explicitMeetingFollowUpSeedsWorkforceWhileOrdinaryChatDoesNot() {
        ObjectMapper json = new ObjectMapper();
        PersistentMeetingStore store = new PersistentMeetingStore(temp.resolve("handoff"), json);
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
        MeetingRoleDeliberator fake = new MeetingRoleDeliberator() {
            @Override public Deliberation deliberate(String role, String purpose, String context) {
                return new Deliberation(role + " assessment", "provider:test:" + role);
            }
            @Override public Deliberation synthesize(String purpose, List<MeetingRecord.Contribution> contributions, String context) {
                return new Deliberation("Implement the agreed gateway routing correction with tests.", "provider:test:synthesis");
            }
        };
        WorkplaceMeetingService service = new WorkplaceMeetingService(store, fake, handoff);

        String meetingRequest = "Mời Head of Technology và Head of Operations họp về Telegram routing.";
        MetatronInteraction meetingInteraction = new MetatronInteraction(
                new ActorRef("founder", ActorRef.ActorType.HUMAN),
                new ActorRef("workforce-head", ActorRef.ActorType.WORKER),
                "organization:metatron", "conversation:founder:handoff", "telegram",
                "telegram:user:1", "telegram:chat:1", "telegram:update:meeting-create", meetingRequest);
        service.handle(meetingInteraction, "");

        MeetingRecord meeting = service.findByExternalMessageReference("telegram:update:meeting-create").orElseThrow();
        String followUp = "Triển khai " + meeting.followUpReference() + " cho Workforce thực hiện.";
        assertTrue(service.supports(followUp));
        assertFalse(service.supports(meeting.followUpReference()));
        assertFalse(service.supports("Fix Telegram routing now"));
        assertEquals(0, submissions.get());

        MetatronInteraction followUpInteraction = new MetatronInteraction(
                new ActorRef("founder", ActorRef.ActorType.HUMAN),
                new ActorRef("workforce-head", ActorRef.ActorType.WORKER),
                "organization:metatron", "conversation:founder:handoff", "telegram",
                "telegram:user:1", "telegram:chat:1", "telegram:update:meeting-follow-up", followUp);
        String response = service.handle(followUpInteraction, "");

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
        MeetingRoleDeliberator fake = new MeetingRoleDeliberator() {
            @Override public Deliberation deliberate(String role, String purpose, String context) {
                return new Deliberation("legacy deliberation", "provider:test:legacy");
            }
            @Override public Deliberation converse(String role, String userMessage, String context) {
                if (userMessage.toLowerCase().contains("trò chuyện") || userMessage.toLowerCase().contains("tro chuyen")) {
                    return new Deliberation("Có tao đây. Mày muốn bàn gì về Gateway?", "provider:test:conversation:1");
                }
                return new Deliberation("Ừ, tao đang nghe. Vấn đề routing mày muốn đào sâu chỗ nào?", "provider:test:conversation:2");
            }
            @Override public Deliberation synthesize(String purpose, List<MeetingRecord.Contribution> contributions, String context) {
                return new Deliberation("must not run for live one-role conversation", "provider:test:synthesis");
            }
        };
        WorkplaceMeetingService service = new WorkplaceMeetingService(store, fake);

        MetatronInteraction open = new MetatronInteraction(
                new ActorRef("founder", ActorRef.ActorType.HUMAN),
                new ActorRef("workforce-head", ActorRef.ActorType.WORKER),
                "organization:metatron", "conversation:live:gateway", "telegram",
                "telegram:user:1", "telegram:chat:1", "telegram:update:live-1",
                "Cho tao trò chuyện với Head of Gateway");

        String first = service.handle(open, "old unrelated bios audit context");
        assertTrue(first.startsWith("🏛 **Head of Gateway**"));
        assertTrue(first.contains("Có tao đây"));
        assertFalse(first.contains("COMMUNICATION INITIATION"));
        assertFalse(first.contains("GOVERNANCE OBJECTIVES"));
        assertFalse(first.contains("case-"));
        assertTrue(service.hasActiveConversationMeeting("conversation:live:gateway"));

        MeetingRecord active = service.findByExternalMessageReference("telegram:update:live-1").orElseThrow();
        assertEquals(MeetingRecord.Status.ACTIVE, active.status());
        assertEquals(2, active.participants().size());
        assertTrue(active.recommendation().isBlank());
        assertTrue(active.actionItems().isEmpty());

        MetatronInteraction next = new MetatronInteraction(
                new ActorRef("founder", ActorRef.ActorType.HUMAN),
                new ActorRef("workforce-head", ActorRef.ActorType.WORKER),
                "organization:metatron", "conversation:live:gateway", "telegram",
                "telegram:user:1", "telegram:chat:1", "telegram:update:live-2",
                "Tao thấy Telegram routing vẫn ngu. Mày thấy root cause ở đâu?");

        String second = service.handle(next, "recent live meeting context");
        assertTrue(second.startsWith("🏛 **Head of Gateway**"));
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
        assertTrue(service.supportsInMeetingMode("Head of tech, create for me head of gateway"));
        assertTrue(WorkplaceMeetingService.requestedRoles("Head of tech, create for me head of gateway")
                .stream().anyMatch(role -> role.equals("Head of Gateway")));
        assertFalse(service.supports("Let's have a meeting sometime."));
        assertFalse(service.supports("Finance outlook this week?"));
    }
}
