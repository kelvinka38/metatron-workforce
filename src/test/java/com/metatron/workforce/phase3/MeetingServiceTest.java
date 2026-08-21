package com.metatron.workforce.phase3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class MeetingServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-21T06:00:00Z");
    private static final ActorRef HUMAN = new ActorRef("human-1", ActorRef.ActorType.HUMAN);
    private static final ActorRef WORKER = new ActorRef("worker-1", ActorRef.ActorType.WORKER);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void createsScheduledMeetingWithAuthorizationContext() {
        MeetingService service = new MeetingService(
                (actor, target, org) -> AuthorizationContext.allowed("auth-1"), CLOCK);

        Meeting meeting = service.createMeeting(
                HUMAN, List.of(HUMAN, WORKER), "org-1", "Planning", "Agenda",
                NOW.plusSeconds(3600), NOW.plusSeconds(7200));

        assertEquals(Meeting.MeetingState.SCHEDULED, meeting.state());
        assertEquals(NOW, meeting.createdAt());
        assertEquals(List.of(HUMAN, WORKER), meeting.participants());
    }

    @Test
    void rejectsUnauthorizedMeetingCreation() {
        MeetingService service = new MeetingService(
                (actor, target, org) -> AuthorizationContext.denied("auth-denied"), CLOCK);

        assertThrows(SecurityException.class, () -> service.createMeeting(
                HUMAN, List.of(HUMAN, WORKER), "org-1", "Planning", "Agenda",
                NOW.plusSeconds(3600), NOW.plusSeconds(7200)));
        assertEquals(0, service.meetings().size());
    }

    @Test
    void preservesScheduledAndActualTimesAcrossHoldAndClose() {
        MeetingService service = new MeetingService(
                (actor, target, org) -> AuthorizationContext.allowed("auth-1"), CLOCK);
        Instant scheduledStart = NOW.plusSeconds(3600);
        Instant scheduledEnd = NOW.plusSeconds(7200);
        Instant actualStart = NOW.plusSeconds(3900);
        Instant actualEnd = NOW.plusSeconds(7500);

        Meeting meeting = service.createMeeting(
                HUMAN, List.of(HUMAN, WORKER), "org-1", "Planning", "Agenda",
                scheduledStart, scheduledEnd);
        meeting = service.holdMeeting(meeting.meetingId(), actualStart);
        meeting = service.closeMeeting(meeting.meetingId(), actualEnd, "minutes-1");

        assertEquals(Meeting.MeetingState.CLOSED, meeting.state());
        assertEquals(scheduledStart, meeting.scheduledStart());
        assertEquals(scheduledEnd, meeting.scheduledEnd());
        assertEquals(actualStart, meeting.actualStart());
        assertEquals(actualEnd, meeting.actualEnd());
        assertEquals("minutes-1", meeting.minutesReference());
    }

    @Test
    void cannotCancelHeldMeeting() {
        MeetingService service = new MeetingService(
                (actor, target, org) -> AuthorizationContext.allowed("auth-1"), CLOCK);
        Meeting meeting = service.createMeeting(
                HUMAN, List.of(HUMAN, WORKER), "org-1", "Planning", "Agenda",
                NOW.plusSeconds(3600), NOW.plusSeconds(7200));

        service.holdMeeting(meeting.meetingId(), NOW.plusSeconds(3900));

        assertThrows(IllegalStateException.class, () -> service.cancelMeeting(meeting.meetingId()));
    }
}
