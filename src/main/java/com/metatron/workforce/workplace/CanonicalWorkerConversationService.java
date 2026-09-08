package com.metatron.workforce.workplace;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.interaction.intelligence.WorkerIntelligenceService;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Live institutional conversation through a real canonical Worker.
 *
 * Meeting never asks a provider to "pretend to be" a role. The requested Worker must exist,
 * be ACTIVE, hold an ACTIVE institutional participation, and have a durable runtime/tool profile
 * binding. Cognition then goes through the WorkerIntelligenceService with requester=workerId.
 */
@Service
public final class CanonicalWorkerConversationService implements WorkerConversationGateway {
    private final WorkforceCoreService core;
    private final WorkerRuntimeProfileBindingService runtimeProfiles;
    private final WorkerIntelligenceService intelligence;

    public CanonicalWorkerConversationService(
            WorkforceCoreService core,
            WorkerRuntimeProfileBindingService runtimeProfiles,
            WorkerIntelligenceService intelligence) {
        this.core = Objects.requireNonNull(core, "core");
        this.runtimeProfiles = Objects.requireNonNull(runtimeProfiles, "runtimeProfiles");
        this.intelligence = Objects.requireNonNull(intelligence, "intelligence");
    }

    @Override
    public Reply converse(String workerId, String role, String userMessage, String conversationContext) {
        WorkforceCoreService.Worker worker = core.worker(workerId);
        if (worker.status() != WorkforceCoreService.WorkerStatus.ACTIVE) {
            throw new IllegalStateException("meeting_worker_not_active:" + workerId + ":status=" + worker.status());
        }

        List<WorkforceCoreService.Participation> activeParticipations = core.participations(workerId).stream()
                .filter(p -> p.status() == WorkforceCoreService.ParticipationStatus.ACTIVE)
                .toList();
        if (activeParticipations.isEmpty()) {
            throw new IllegalStateException("meeting_worker_has_no_active_participation:" + workerId);
        }

        WorkforceCoreService.Participation participation = selectParticipation(activeParticipations, role);
        WorkerRuntimeProfileBindingService.Binding runtime = runtimeProfiles.requireBinding(workerId);

        List<String> capabilityRefs = core.capabilities(workerId).stream()
                .filter(capability -> capability.level() > 0)
                .map(WorkforceCoreService.CapabilityAttestation::capabilityRef)
                .distinct()
                .sorted()
                .toList();

        List<String> evidence = new ArrayList<>();
        evidence.add("worker:" + workerId + ":status=ACTIVE");
        evidence.add("worker-participation:" + participation.participationId()
                + ":role=" + participation.roleRef()
                + ":position=" + participation.positionRef());
        evidence.add("worker-runtime-profile:" + runtime.profile().profileRef());
        evidence.add("worker-runtime-actions:" + runtime.profile().actionRefs().stream().sorted().toList());
        capabilityRefs.forEach(capability -> evidence.add("worker-capability:" + capability));

        String instructions = """
                You are the real institutional Worker identified below, speaking directly with the Human in a live Meeting.
                This is a conversation, not a memo, report, governance notice, meeting minutes, or provider persona.
                Preserve the Worker's actual institutional role, accountability and authority boundary.
                Answer the Human's latest message naturally and concisely. Ask a useful follow-up only when needed.
                Do not invent actions, approvals, evidence, tools, memory, execution or authority.
                Do not claim work was executed unless durable execution evidence actually exists in the supplied context.
                Do not expose or impersonate the underlying LLM/provider as the institutional actor.
                Do not drag unrelated old Objectives, Cases or repository audits into the conversation unless the Human refers to them.
                """;

        String context = """
                CANONICAL WORKER
                worker_id=%s
                participant_id=%s
                institutional_role=%s
                role_ref=%s
                position_ref=%s
                runtime_profile=%s
                capabilities=%s

                HUMAN MESSAGE
                %s

                MEETING CONTEXT
                %s
                """.formatted(
                workerId,
                worker.participantId(),
                role == null ? "" : role,
                participation.roleRef(),
                participation.positionRef(),
                runtime.profile().profileRef(),
                capabilityRefs,
                safe(userMessage),
                safe(conversationContext));

        WorkerIntelligenceService.Response response = intelligence.reason(new WorkerIntelligenceService.Request(
                workerId,
                "worker.live.conversation",
                instructions,
                context,
                List.copyOf(evidence)));

        List<String> replyEvidence = new ArrayList<>(response.evidenceReferences());
        replyEvidence.add("worker-conversation-request:" + response.requestReference());
        return new Reply(response.text(), response.requestReference(), List.copyOf(replyEvidence));
    }

    private static WorkforceCoreService.Participation selectParticipation(
            List<WorkforceCoreService.Participation> active,
            String requestedRole) {
        String wanted = normalize(requestedRole);
        if (!wanted.isBlank()) {
            List<WorkforceCoreService.Participation> matches = active.stream()
                    .filter(p -> normalize(p.roleRef()).contains(wanted)
                            || wanted.contains(normalize(p.roleRef()))
                            || normalize(p.positionRef()).contains(wanted)
                            || wanted.contains(normalize(p.positionRef())))
                    .toList();
            if (matches.size() == 1) return matches.getFirst();
        }
        return active.stream()
                .sorted(Comparator.comparing(WorkforceCoreService.Participation::participationId))
                .findFirst()
                .orElseThrow();
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
