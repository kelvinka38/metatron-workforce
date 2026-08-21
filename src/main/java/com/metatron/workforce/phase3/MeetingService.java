package com.metatron.workforce.phase3;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Coordinates meeting lifecycle without manufacturing decision or execution authority. */
public final class MeetingService {
    private final AuthorizationPolicy authorizationPolicy;
    private final Clock clock;
    private final List<Meeting> meetings = new ArrayList<>();

    public MeetingService(AuthorizationPolicy authorizationPolicy, Clock clock) {
        this.authorizationPolicy = Objects.requireNonNull(authorizationPolicy, "authorizationPolicy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Meeting createMeeting(
            ActorRef organizer,
            List<ActorRef> participants,
            String organizationContextId,
            String purpose,
            String agenda,
            Instant scheduledStart,
            Instant scheduledEnd) {
        Objects.requireNonNull(organizer, "organizer");
        Objects.requireNonNull(participants, "participants");
        if (!participants.contains(organizer)) {
            throw new IllegalArgumentException("organizer must be a participant");
        }
        ActorRef target = participants.stream()
                .filter(actor -> !actor.equals(organizer))
                .findFirst()
                .orElse(organizer);
        requireAllowed(authorizationPolicy.authorize(organizer, target, organizationContextId));

        Meeting meeting = new Meeting(
                UUID.randomUUID().toString(), organizer, participants, organizationContextId,
                purpose, agenda, scheduledStart, scheduledEnd, null, null,
                Meeting.MeetingState.SCHEDULED, null, List.of(), List.of(), List.of(), List.of(), Instant.now(clock));
        meetings.add(meeting);
        return meeting;
    }

    public Meeting holdMeeting(String meetingId, ActorRef actor, Instant actualStart) {
        Meeting meeting = find(meetingId);
        requireActorCanManageMeeting(meeting, actor);
        if (meeting.state() != Meeting.MeetingState.SCHEDULED) {
            throw new IllegalStateException("meeting must be SCHEDULED before it can be HELD");
        }
        return replace(meeting, actualStart, meeting.actualEnd(), Meeting.MeetingState.HELD,
                meeting.minutesReference(), meeting.discussionReferences(), meeting.decisionReferences(),
                meeting.actionItemReferences(), meeting.evidenceReferences());
    }

    public Meeting holdMeeting(String meetingId, Instant actualStart) {
        Meeting meeting = find(meetingId);
        return holdMeeting(meetingId, meeting.organizer(), actualStart);
    }

    public Meeting closeMeeting(String meetingId, ActorRef actor, Instant actualEnd, String minutesReference) {
        Meeting meeting = find(meetingId);
        requireActorCanManageMeeting(meeting, actor);
        if (meeting.state() != Meeting.MeetingState.HELD && meeting.state() != Meeting.MeetingState.MINUTES_PENDING) {
            throw new IllegalStateException("meeting must be HELD or MINUTES_PENDING before close");
        }
        if (actualEnd == null) throw new NullPointerException("actualEnd");
        if (meeting.actualStart() != null && actualEnd.isBefore(meeting.actualStart())) {
            throw new IllegalArgumentException("actualEnd must not precede actualStart");
        }
        if (minutesReference == null || minutesReference.isBlank()) {
            throw new IllegalArgumentException("minutesReference is required to close a meeting");
        }
        return replace(meeting, meeting.actualStart(), actualEnd, Meeting.MeetingState.CLOSED,
                minutesReference, meeting.discussionReferences(), meeting.decisionReferences(),
                meeting.actionItemReferences(), meeting.evidenceReferences());
    }

    public Meeting closeMeeting(String meetingId, Instant actualEnd, String minutesReference) {
        Meeting meeting = find(meetingId);
        return closeMeeting(meetingId, meeting.organizer(), actualEnd, minutesReference);
    }

    public Meeting cancelMeeting(String meetingId, ActorRef actor) {
        Meeting meeting = find(meetingId);
        requireActorCanManageMeeting(meeting, actor);
        if (meeting.state() == Meeting.MeetingState.HELD || meeting.state() == Meeting.MeetingState.CLOSED) {
            throw new IllegalStateException("held or closed meeting cannot be cancelled");
        }
        return replace(meeting, meeting.actualStart(), meeting.actualEnd(), Meeting.MeetingState.CANCELLED,
                meeting.minutesReference(), meeting.discussionReferences(), meeting.decisionReferences(),
                meeting.actionItemReferences(), meeting.evidenceReferences());
    }

    public Meeting cancelMeeting(String meetingId) {
        Meeting meeting = find(meetingId);
        return cancelMeeting(meetingId, meeting.organizer());
    }

    public List<Meeting> meetings() { return List.copyOf(meetings); }

    private Meeting find(String meetingId) {
        return meetings.stream().filter(item -> item.meetingId().equals(meetingId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("meeting not found: " + meetingId));
    }

    private void requireActorCanManageMeeting(Meeting meeting, ActorRef actor) {
        Objects.requireNonNull(actor, "actor");
        if (!meeting.organizer().equals(actor)) {
            throw new SecurityException("only the meeting organizer may manage the meeting lifecycle");
        }
        ActorRef target = meeting.participants().stream()
                .filter(participant -> !participant.equals(actor))
                .findFirst()
                .orElse(actor);
        requireAllowed(authorizationPolicy.authorize(actor, target, meeting.organizationContextId()));
    }

    private Meeting replace(Meeting old, Instant actualStart, Instant actualEnd, Meeting.MeetingState state,
                            String minutes, List<String> discussions, List<String> decisions,
                            List<String> actions, List<String> evidence) {
        Meeting next = new Meeting(old.meetingId(), old.organizer(), old.participants(), old.organizationContextId(),
                old.purpose(), old.agenda(), old.scheduledStart(), old.scheduledEnd(), actualStart, actualEnd,
                state, minutes, discussions, decisions, actions, evidence, old.createdAt());
        meetings.set(meetings.indexOf(old), next);
        return next;
    }

    private static void requireAllowed(AuthorizationContext authorization) {
        Objects.requireNonNull(authorization, "authorization");
        if (!authorization.allowed()) throw new SecurityException(
                "meeting action denied by authorization context " + authorization.authorizationId());
    }
}
