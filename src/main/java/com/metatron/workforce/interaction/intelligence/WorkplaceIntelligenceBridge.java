package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.phase3.ActorRef;
import com.metatron.workforce.phase3.Meeting;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Intelligence support for Workplace-owned meetings.
 *
 * This bridge never owns Meeting lifecycle, decisions, actions, minutes, authority or execution.
 * It consumes Workplace references and returns an Intelligence result linked to the Meeting.
 */
public final class WorkplaceIntelligenceBridge {
    private final IntelligenceFabric fabric;

    public WorkplaceIntelligenceBridge(IntelligenceFabric fabric) {
        this.fabric = Objects.requireNonNull(fabric, "fabric");
    }

    public MeetingIntelligenceResult analyzeMeeting(
            Meeting meeting,
            ActorRef requester,
            String objective,
            IntelligenceDepth depth,
            CollaborationMode collaborationMode,
            List<LlmProvider> requestedProviders,
            int maxProviders) {

        Objects.requireNonNull(meeting, "meeting");
        Objects.requireNonNull(requester, "requester");
        Objects.requireNonNull(objective, "objective");
        Objects.requireNonNull(depth, "depth");
        Objects.requireNonNull(collaborationMode, "collaborationMode");
        Objects.requireNonNull(requestedProviders, "requestedProviders");
        if (objective.isBlank()) throw new IllegalArgumentException("objective must not be blank");
        if (!meeting.participants().contains(requester)) {
            throw new SecurityException("meeting intelligence requester must be a Workplace participant");
        }
        if (meeting.state() == Meeting.MeetingState.CANCELLED) {
            throw new IllegalStateException("cancelled meeting cannot request deliberation intelligence");
        }

        List<String> evidence = new ArrayList<>(meeting.evidenceReferences());
        String context = renderMeetingContext(meeting);
        IntelligenceRequest request = new IntelligenceRequest(
                "meeting-intelligence-" + UUID.randomUUID(),
                actorReference(requester),
                IntelligenceMode.REASONING,
                collaborationMode,
                objective.trim(),
                context,
                List.copyOf(evidence),
                "workplace.meeting.deliberation",
                IntelligenceConsequencePolicy.forNonConsequentialMode(IntelligenceMode.REASONING),
                latencyBudget(depth),
                costBudget(depth),
                "",
                "meeting intelligence support with evidence-linked disagreement preserved",
                List.copyOf(requestedProviders),
                maxProviders,
                false);

        IntelligenceResult result = fabric.execute(request);
        return new MeetingIntelligenceResult(
                "meeting:" + meeting.meetingId(),
                meeting.organizationContextId(),
                result);
    }

    private static String renderMeetingContext(Meeting meeting) {
        return "WORKPLACE MEETING CONTEXT (owned by Workplace; references only)\n"
                + "meeting_ref=meeting:" + meeting.meetingId() + "\n"
                + "organization_context=" + meeting.organizationContextId() + "\n"
                + "state=" + meeting.state() + "\n"
                + "purpose=" + meeting.purpose() + "\n"
                + "agenda=" + meeting.agenda() + "\n"
                + "organizer=" + actorReference(meeting.organizer()) + "\n"
                + "participants=" + meeting.participants().stream().map(WorkplaceIntelligenceBridge::actorReference).toList() + "\n"
                + "minutes_ref=" + String.valueOf(meeting.minutesReference()) + "\n"
                + "discussion_refs=" + meeting.discussionReferences() + "\n"
                + "decision_refs=" + meeting.decisionReferences() + "\n"
                + "action_item_refs=" + meeting.actionItemReferences() + "\n"
                + "evidence_refs=" + meeting.evidenceReferences() + "\n"
                + "BOUNDARY: Meeting records, decisions and actions remain Workplace/institutional state. Intelligence may analyze but cannot mutate or authorize them.";
    }

    private static String actorReference(ActorRef actor) {
        return actor.type().name().toLowerCase(java.util.Locale.ROOT) + ":" + actor.actorId();
    }

    private static String latencyBudget(IntelligenceDepth depth) {
        return switch (depth) {
            case FAST -> "interactive-fast";
            case ANALYZE -> "interactive-analysis";
            case DEEP -> "extended-investigation";
        };
    }

    private static String costBudget(IntelligenceDepth depth) {
        return switch (depth) {
            case FAST -> "standard";
            case ANALYZE -> "expanded";
            case DEEP -> "deep";
        };
    }

    public record MeetingIntelligenceResult(
            String meetingReference,
            String organizationContextId,
            IntelligenceResult intelligenceResult) {
        public MeetingIntelligenceResult {
            Objects.requireNonNull(meetingReference, "meetingReference");
            Objects.requireNonNull(organizationContextId, "organizationContextId");
            Objects.requireNonNull(intelligenceResult, "intelligenceResult");
        }
    }
}
