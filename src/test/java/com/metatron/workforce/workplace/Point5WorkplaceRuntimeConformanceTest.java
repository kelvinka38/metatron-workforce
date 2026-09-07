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
        assertTrue(service.supportsInMeetingMode("Head of tech, create for me head of gateway"));
        assertTrue(WorkplaceMeetingService.requestedRoles("Head of tech, create for me head of gateway")
                .stream().anyMatch(role -> role.equals("Head of Gateway")));
        assertFalse(service.supports("Let's have a meeting sometime."));
        assertFalse(service.supports("Finance outlook this week?"));
    }
}
