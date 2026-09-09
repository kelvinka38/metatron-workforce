package com.metatron.workforce.workplace;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.interaction.intelligence.WorkerIntelligenceService;
import com.metatron.workforce.runtime.RuntimeCapacityCoordinator;
import com.metatron.workforce.runtime.RuntimeInstance;
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
    private final RuntimeCapacityCoordinator runtimeCapacity;
    private final WorkerIntelligenceService intelligence;

    public CanonicalWorkerConversationService(
            WorkforceCoreService core,
            WorkerRuntimeProfileBindingService runtimeProfiles,
            RuntimeCapacityCoordinator runtimeCapacity,
            WorkerIntelligenceService intelligence) {
        this.core = Objects.requireNonNull(core, "core");
        this.runtimeProfiles = Objects.requireNonNull(runtimeProfiles, "runtimeProfiles");
        this.runtimeCapacity = Objects.requireNonNull(runtimeCapacity, "runtimeCapacity");
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
        RuntimeInstance liveRuntime = runtimeCapacity.ensureRunning(workerId);

        List<String> capabilityRefs = core.capabilities(workerId).stream()
                .filter(capability -> capability.level() > 0)
                .map(WorkforceCoreService.Capability::capabilityRef)
                .distinct()
                .sorted()
                .toList();

        List<String> evidence = new ArrayList<>();
        evidence.add("worker:" + workerId + ":status=ACTIVE");
        evidence.add("worker-participation:" + participation.participationId()
                + ":role=" + participation.roleRef()
                + ":position=" + participation.positionRef());
        evidence.add("worker-runtime-profile:" + runtime.profile().profileRef());
        evidence.add("worker-runtime-instance:" + liveRuntime.runtimeId() + ":state=" + liveRuntime.state().name());
        evidence.add("worker-runtime-actions:" + runtime.profile().actionRefs().stream().sorted().toList());
        capabilityRefs.forEach(capability -> evidence.add("worker-capability:" + capability));
        String institutionalGrounding = institutionalGrounding(participation);
        if (!institutionalGrounding.isBlank()) {
            evidence.add("institutional-context:metatron-institution/06_GATEWAY/SOT.md");
            evidence.add("institutional-context:metatron-institution/06_GATEWAY/GATEWAY/DOMAIN_OWNERSHIP_MATRIX.md");
        }

        String instructions = """
                You are the real institutional Worker identified below, speaking directly with the Human in a live Meeting.
                This is a conversation, not a memo, report, governance notice, meeting minutes, or provider persona.
                Preserve the Worker's actual institutional role, accountability and authority boundary.
                Answer the Human's latest message naturally and concisely. Ask a useful follow-up only when needed.
                Do not invent actions, approvals, evidence, tools, memory, execution or authority.
                Do not claim work was executed unless durable execution evidence actually exists in the supplied context.
                Do not claim you reviewed a document, repository, org chart, queue, runtime policy or operational state unless that exact source/evidence is supplied in context.
                Treat institutional ownership boundaries as hard constraints, not suggestions.
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
                runtime_id=%s
                runtime_state=%s
                capabilities=%s

                AUTHORITATIVE INSTITUTIONAL GROUNDING
                %s

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
                liveRuntime.runtimeId(),
                liveRuntime.state().name(),
                capabilityRefs,
                institutionalGrounding,
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
        return new Reply(response.text(), response.requestReference(), List.copyOf(replyEvidence), liveRuntime.runtimeId());
    }

    private static String institutionalGrounding(WorkforceCoreService.Participation participation) {
        if ("ROLE-HEAD-OF-GATEWAY".equals(participation.roleRef())
                || "position:gateway-director".equals(participation.positionRef())) {
            return """
                    authority_source=kelvinka38/metatron-institution/06_GATEWAY/SOT.md
                    ownership_source=kelvinka38/metatron-institution/06_GATEWAY/GATEWAY/DOMAIN_OWNERSHIP_MATRIX.md
                    gateway_mandate=controlled institutional boundary crossing with the outside world
                    gateway_owns=boundary enforcement and Gateway-specific operational outcomes
                    gateway_does_not_own=BIOS product; Workforce/Worker identity; Workplace/Meeting semantics; Objective/Work; Intelligence; Execution semantics; Knowledge truth; downstream product cognition
                    bios_boundary=BIOS is a downstream Product/Node and is not the Gateway SoT or Gateway institutional owner
                    execution_rule=conversation is not execution; an execution claim requires supplied durable execution evidence
                    source_claim_rule=do not say a document/repository/org-chart was reviewed unless exact source evidence is supplied
                    """;
        }
        return "";
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
