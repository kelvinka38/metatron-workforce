package com.metatron.workforce.workplace;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.interaction.intelligence.WorkerIntelligenceService;
import com.metatron.workforce.runtime.RuntimeCapacityCoordinator;
import com.metatron.workforce.runtime.RuntimeInstance;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final InstitutionalRoleGrounding institutionalGrounding;

    @Autowired
    public CanonicalWorkerConversationService(
            WorkforceCoreService core,
            WorkerRuntimeProfileBindingService runtimeProfiles,
            RuntimeCapacityCoordinator runtimeCapacity,
            WorkerIntelligenceService intelligence,
            InstitutionalRoleGrounding institutionalGrounding) {
        this.core = Objects.requireNonNull(core, "core");
        this.runtimeProfiles = Objects.requireNonNull(runtimeProfiles, "runtimeProfiles");
        this.runtimeCapacity = Objects.requireNonNull(runtimeCapacity, "runtimeCapacity");
        this.intelligence = Objects.requireNonNull(intelligence, "intelligence");
        this.institutionalGrounding = Objects.requireNonNull(institutionalGrounding, "institutionalGrounding");
    }

    /**
     * Test/backward-compatible constructor only. Production Spring wiring uses the grounded constructor above.
     */
    CanonicalWorkerConversationService(
            WorkforceCoreService core,
            WorkerRuntimeProfileBindingService runtimeProfiles,
            RuntimeCapacityCoordinator runtimeCapacity,
            WorkerIntelligenceService intelligence) {
        this(core, runtimeProfiles, runtimeCapacity, intelligence,
                (roleRef, positionRef, requestedRole, message) -> InstitutionalRoleGrounding.Grounding.available(
                        "test-only",
                        "TEST-ONLY INSTITUTIONAL GROUNDING",
                        List.of("institutional-source:test-only")));
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

        InstitutionalRoleGrounding.Grounding grounding = institutionalGrounding.resolve(
                participation.roleRef(), participation.positionRef(), role, userMessage);
        grounding.evidenceReferences().stream()
                .filter(ref -> ref != null && !ref.isBlank())
                .filter(ref -> !evidence.contains(ref))
                .forEach(evidence::add);
        if (!grounding.available()) {
            return new Reply(
                    "Canonical institutional grounding is unavailable for this Worker (" + grounding.reason()
                            + "). I will not answer as " + (role == null || role.isBlank() ? participation.roleRef() : role)
                            + " from ungrounded model memory.",
                    "institutional-grounding-blocked:" + workerId,
                    List.copyOf(evidence),
                    liveRuntime.runtimeId());
        }

        String instructions = """
                You are the real institutional Worker identified below, speaking directly with the Human in a live Meeting.
                This is a conversation, not a memo, report, governance notice, meeting minutes, or provider persona.
                Preserve the Worker's actual institutional role, accountability and authority boundary.
                The CANONICAL INSTITUTIONAL GROUNDING below is authoritative for domain ownership and scope.
                Treat that grounding as a scope ceiling: do not absorb semantics owned by another institutional domain.
                Answer the Human's latest message naturally and concisely. Ask a useful follow-up only when needed.
                Do not invent repository files, org charts, actions, approvals, evidence, tools, memory, execution or authority.
                Retrieved canonical source evidence proves only that the source was retrieved; it does not prove an external action happened.
                Do not claim work is running, executed, audited, generated, deployed, fixed, committed or otherwise performed
                unless a durable successful action/execution receipt or Observation reference exists in the supplied evidence.
                Without such a receipt, describe action only as a proposal/intention and state that execution has not occurred.
                Do not infer behavior or approval requirements from a runtime profile name alone.
                FUNCTION does not imply DEDICATED WORKER; never invent staff from an organizational function.
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

                CANONICAL INSTITUTIONAL GROUNDING
                domain=%s
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
                grounding.domain(),
                grounding.context(),
                safe(userMessage),
                safe(conversationContext));

        WorkerIntelligenceService.Response response = intelligence.reason(new WorkerIntelligenceService.Request(
                workerId,
                "worker.live.conversation",
                instructions,
                context,
                List.copyOf(evidence)));

        List<String> replyEvidence = new ArrayList<>(response.evidenceReferences());
        grounding.evidenceReferences().stream()
                .filter(ref -> ref != null && !ref.isBlank())
                .filter(ref -> !replyEvidence.contains(ref))
                .forEach(replyEvidence::add);
        replyEvidence.add("worker-conversation-request:" + response.requestReference());
        String truthfulText = WorkerConversationExecutionClaimGuard.enforce(response.text(), replyEvidence);
        if (!truthfulText.equals(response.text())) {
            replyEvidence.add("worker-conversation-claim-guard:execution-claim-suppressed");
        }
        return new Reply(truthfulText, response.requestReference(), List.copyOf(replyEvidence), liveRuntime.runtimeId());
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
