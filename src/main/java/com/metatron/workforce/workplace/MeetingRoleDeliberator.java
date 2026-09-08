package com.metatron.workforce.workplace;

import com.metatron.workforce.interaction.intelligence.CollaborationMode;
import com.metatron.workforce.interaction.intelligence.IntelligenceConsequencePolicy;
import com.metatron.workforce.interaction.intelligence.IntelligenceFabric;
import com.metatron.workforce.interaction.intelligence.IntelligenceMode;
import com.metatron.workforce.interaction.intelligence.IntelligenceRequest;
import com.metatron.workforce.interaction.intelligence.IntelligenceResult;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Role identity is institutional; frontier providers are consumed only through Intelligence. */
public interface MeetingRoleDeliberator {
    Deliberation deliberate(String role, String purpose, String conversationContext);
    Deliberation synthesize(String purpose, List<MeetingRecord.Contribution> contributions, String conversationContext);

    /** Live Meeting conversation: a real canonical Worker speaks from its institutional role. */
    default Deliberation converse(String workerId, String role, String userMessage, String meetingContext) {
        return converse(role, userMessage, meetingContext);
    }

    /** Compatibility shape for existing test doubles. Production Meeting calls the worker-bound overload above. */
    default Deliberation converse(String role, String userMessage, String meetingContext) {
        return deliberate(role, userMessage, meetingContext);
    }

    record Deliberation(String text, String providerReference) {
        public Deliberation {
            if (text == null || text.isBlank()) throw new IllegalArgumentException("deliberation text required");
            providerReference = providerReference == null ? "" : providerReference;
        }
    }

    static MeetingRoleDeliberator intelligenceBacked(IntelligenceFabric fabric, int providerBudget) {
        return new IntelligenceBacked(fabric, providerBudget);
    }

    final class IntelligenceBacked implements MeetingRoleDeliberator {
        private final IntelligenceFabric fabric;
        private final int providerBudget;

        IntelligenceBacked(IntelligenceFabric fabric, int providerBudget) {
            this.fabric = Objects.requireNonNull(fabric, "fabric");
            if (providerBudget < 1) throw new IllegalStateException(
                    "Meeting Room requires configured institutional Intelligence capacity");
            this.providerBudget = providerBudget;
        }

        @Override
        public Deliberation deliberate(String role, String purpose, String conversationContext) {
            String instructions = """
                    You are participating in an institutional Metatron Meeting Room as the named role below.
                    Role identity is institutional and is not the same as model/provider identity.
                    Analyze only from this role's professional accountability and constraints.
                    State: assessment, material risks, evidence/assumptions, recommendation, and disagreement you expect from other roles.
                    Do not claim authority to execute. Do not fabricate evidence, actions, approvals or consensus.
                    Keep the contribution concise but substantive.
                    ROLE: %s
                    """.formatted(role);
            String objective = "MEETING PURPOSE:\n" + purpose
                    + "\n\nCONVERSATION CONTEXT:\n" + safeContext(conversationContext);
            return complete("meeting.role.deliberation", "workplace:meeting-room", instructions, objective);
        }

        @Override
        public Deliberation converse(String workerId, String role, String userMessage, String meetingContext) {
            String instructions = """
                    You are the named institutional Metatron role in a live conversation with the Human.
                    Speak directly as that role, like a real colleague in the room. This is NOT a memo, report, meeting minutes, governance notice, or formal communication.
                    Answer the Human's latest message naturally and concisely. You may ask a useful follow-up question when appropriate.
                    Preserve the role's real accountability and constraints, but do not recite protocol unless the Human asks or it is materially necessary.
                    Never invent actions, approvals, evidence, or authority. Do not claim execution unless it actually happened.
                    Do not introduce an old case, objective, repository audit, or institutional context unless the Human explicitly refers to it or it is directly necessary to answer the latest message.
                    Do not add headings such as COMMUNICATION INITIATION, GOVERNANCE OBJECTIVES, REQUEST FOR INPUT, To/From/Reference, or status boilerplate.
                    ROLE: %s
                    """.formatted(role);
            String objective = "HUMAN MESSAGE:\n" + userMessage
                    + "\n\nLIVE MEETING CONTEXT:\n" + safeContext(meetingContext);
            return complete("meeting.role.conversation", workerId, instructions, objective);
        }

        @Override
        public Deliberation synthesize(String purpose, List<MeetingRecord.Contribution> contributions,
                                       String conversationContext) {
            Objects.requireNonNull(contributions, "contributions");
            StringBuilder transcript = new StringBuilder();
            for (MeetingRecord.Contribution c : contributions) {
                transcript.append("\n--- ").append(c.role()).append(" ---\n").append(c.text()).append('\n');
            }
            String instructions = """
                    You are the neutral chair/synthesizer of an institutional Metatron Meeting Room.
                    Synthesize the attributed role contributions without inventing consensus.
                    Explicitly preserve meaningful disagreements and unresolved assumptions.
                    Produce: shared ground, disagreements, recommendation, risks, and proposed follow-up.
                    A meeting recommendation is NOT institutional authorization and MUST NOT be described as approval or execution.
                    """;
            String objective = "MEETING PURPOSE:\n" + purpose
                    + "\n\nATTRIBUTED CONTRIBUTIONS:\n" + transcript
                    + "\n\nCONVERSATION CONTEXT:\n" + safeContext(conversationContext);
            return complete("meeting.synthesis", "workplace:meeting-room", instructions, objective);
        }

        private Deliberation complete(String capability, String requester, String instructions, String objective) {
            String requestId = "meeting-intelligence-" + UUID.randomUUID();
            try {
                IntelligenceResult result = fabric.execute(new IntelligenceRequest(
                        requestId,
                        requester,
                        IntelligenceMode.REASONING,
                        CollaborationMode.SINGLE,
                        objective,
                        instructions,
                        List.of("workplace-meeting-input:" + requestId),
                        capability,
                        IntelligenceConsequencePolicy.forNonConsequentialMode(IntelligenceMode.REASONING),
                        "meeting",
                        "bounded",
                        "",
                        "substantive institutional Meeting contribution",
                        List.of(),
                        providerBudget,
                        false));
                String providerReference = "";
                if (!result.providerResults().isEmpty()) {
                    var response = result.providerResults().getFirst().response();
                    providerReference = "provider:" + response.provider().name().toLowerCase(Locale.ROOT)
                            + ":model:" + response.model()
                            + ":request:" + String.valueOf(response.providerRequestReference());
                }
                return new Deliberation(result.text(), providerReference);
            } catch (RuntimeException failure) {
                throw new IllegalStateException(
                        "meeting_intelligence_exhausted:" + compactFailure(failure), failure);
            }
        }

        private static String compactFailure(RuntimeException failure) {
            String message = failure.getMessage();
            String value = failure.getClass().getSimpleName()
                    + (message == null || message.isBlank() ? "" : ":" + message);
            value = value.replaceAll("\\s+", " ").trim();
            return value.length() <= 640 ? value : value.substring(0, 640);
        }

        private static String safeContext(String value) {
            if (value == null || value.isBlank()) return "(none)";
            String trimmed = value.trim();
            return trimmed.length() <= 12000 ? trimmed : trimmed.substring(trimmed.length() - 12000);
        }
    }
}
