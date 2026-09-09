package com.metatron.workforce.workplace;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.interaction.intelligence.WorkerIntelligenceService;
import com.metatron.workforce.operating.WorkerConstitutionRuntimeMaterializer;
import com.metatron.workforce.operating.WorkerConstitutionService;
import com.metatron.workforce.runtime.RuntimeCapacityCoordinator;
import com.metatron.workforce.runtime.RuntimeInstance;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Live channel-neutral institutional conversation through a real canonical Worker.
 *
 * No Workplace surface asks a provider to "pretend to be" a role. The requested Worker must exist,
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
    private final WorkerConstitutionService constitution;
    private final WorkerConstitutionRuntimeMaterializer runtimeConstitution;

    @Autowired
    public CanonicalWorkerConversationService(
            WorkforceCoreService core,
            WorkerRuntimeProfileBindingService runtimeProfiles,
            RuntimeCapacityCoordinator runtimeCapacity,
            WorkerIntelligenceService intelligence,
            InstitutionalRoleGrounding institutionalGrounding,
            WorkerConstitutionService constitution,
            WorkerConstitutionRuntimeMaterializer runtimeConstitution) {
        this.core = Objects.requireNonNull(core, "core");
        this.runtimeProfiles = Objects.requireNonNull(runtimeProfiles, "runtimeProfiles");
        this.runtimeCapacity = Objects.requireNonNull(runtimeCapacity, "runtimeCapacity");
        this.intelligence = Objects.requireNonNull(intelligence, "intelligence");
        this.institutionalGrounding = Objects.requireNonNull(institutionalGrounding, "institutionalGrounding");
        this.constitution = Objects.requireNonNull(constitution, "constitution");
        this.runtimeConstitution = Objects.requireNonNull(runtimeConstitution, "runtimeConstitution");
    }

    /**
     * Test/backward-compatible constructor only. Production Spring wiring uses the grounded constructor above.
     */
    CanonicalWorkerConversationService(
            WorkforceCoreService core,
            WorkerRuntimeProfileBindingService runtimeProfiles,
            RuntimeCapacityCoordinator runtimeCapacity,
            WorkerIntelligenceService intelligence) {
        this.core = Objects.requireNonNull(core, "core");
        this.runtimeProfiles = Objects.requireNonNull(runtimeProfiles, "runtimeProfiles");
        this.runtimeCapacity = Objects.requireNonNull(runtimeCapacity, "runtimeCapacity");
        this.intelligence = Objects.requireNonNull(intelligence, "intelligence");
        this.institutionalGrounding =
                (roleRef, positionRef, requestedRole, message) -> InstitutionalRoleGrounding.Grounding.available(
                        "test-only",
                        "TEST-ONLY INSTITUTIONAL GROUNDING",
                        List.of("institutional-source:test-only"));
        this.constitution = null; // Test/backward constructor only; production requires durable constitution.
        this.runtimeConstitution = null;
    }

    CanonicalWorkerConversationService(
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
        this.constitution = null;
        this.runtimeConstitution = null;
    }

    CanonicalWorkerConversationService(
            WorkforceCoreService core,
            WorkerRuntimeProfileBindingService runtimeProfiles,
            RuntimeCapacityCoordinator runtimeCapacity,
            WorkerIntelligenceService intelligence,
            InstitutionalRoleGrounding institutionalGrounding,
            WorkerConstitutionService constitution) {
        this.core = Objects.requireNonNull(core, "core");
        this.runtimeProfiles = Objects.requireNonNull(runtimeProfiles, "runtimeProfiles");
        this.runtimeCapacity = Objects.requireNonNull(runtimeCapacity, "runtimeCapacity");
        this.intelligence = Objects.requireNonNull(intelligence, "intelligence");
        this.institutionalGrounding = Objects.requireNonNull(institutionalGrounding, "institutionalGrounding");
        this.constitution = Objects.requireNonNull(constitution, "constitution");
        this.runtimeConstitution = null;
    }

    CanonicalWorkerConversationService(
            WorkforceCoreService core,
            WorkerRuntimeProfileBindingService runtimeProfiles,
            RuntimeCapacityCoordinator runtimeCapacity,
            WorkerIntelligenceService intelligence,
            InstitutionalRoleGrounding institutionalGrounding,
            WorkerConstitutionService constitution,
            WorkerConstitutionRuntimeMaterializer runtimeConstitution) {
        this.core = Objects.requireNonNull(core, "core");
        this.runtimeProfiles = Objects.requireNonNull(runtimeProfiles, "runtimeProfiles");
        this.runtimeCapacity = Objects.requireNonNull(runtimeCapacity, "runtimeCapacity");
        this.intelligence = Objects.requireNonNull(intelligence, "intelligence");
        this.institutionalGrounding = Objects.requireNonNull(institutionalGrounding, "institutionalGrounding");
        this.constitution = Objects.requireNonNull(constitution, "constitution");
        this.runtimeConstitution = Objects.requireNonNull(runtimeConstitution, "runtimeConstitution");
    }

    @Override
    public Reply converse(String workerId, String role, String userMessage, String conversationContext) {
        return converse(workerId, role, userMessage, conversationContext, List.of());
    }

    @Override
    public Reply converse(String workerId, String role, String userMessage, String conversationContext,
                          List<String> trustedExecutionEvidence) {
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
        WorkerConstitutionService.ConstitutionContext constitutionContext = null;
        WorkerConstitutionRuntimeMaterializer.RuntimeConstitution runtimeConstitutionContext = null;
        if (runtimeConstitution != null) {
            try {
                runtimeConstitutionContext = runtimeConstitution.materialize(
                        workerId, participation.participationId(), Instant.now());
            } catch (IllegalStateException missingConstitution) {
                return constitutionUnavailable(workerId, missingConstitution);
            }
        } else if (constitution != null) {
            try {
                constitutionContext = constitution.contextFor(workerId, participation.participationId());
            } catch (IllegalStateException missingConstitution) {
                return constitutionUnavailable(workerId, missingConstitution);
            }
        }
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
        if (runtimeConstitutionContext != null) {
            runtimeConstitutionContext.evidenceReferences().stream()
                    .filter(ref -> ref != null && !ref.isBlank())
                    .filter(ref -> !evidence.contains(ref))
                    .forEach(evidence::add);
            evidence.add("worker-constitution-runtime-snapshot:" + runtimeConstitutionContext.snapshotId());
            evidence.add("worker-position-contract:" + runtimeConstitutionContext.positionContract().contractId());
        } else if (constitutionContext != null) {
            constitutionContext.evidenceReferences().stream()
                    .filter(ref -> ref != null && !ref.isBlank())
                    .filter(ref -> !evidence.contains(ref))
                    .forEach(evidence::add);
            evidence.add("worker-position-contract:" + constitutionContext.contract().contractId());
        }
        if (trustedExecutionEvidence != null) {
            trustedExecutionEvidence.stream()
                    .filter(ref -> ref != null && !ref.isBlank())
                    .map(String::trim)
                    .filter(ref -> ref.startsWith("action-fabric:"))
                    .filter(ref -> ref.contains(":worker=" + workerId + ":"))
                    .filter(ref -> ref.endsWith(":success=true") || ref.contains(":success=true:"))
                    .distinct()
                    .limit(200)
                    .filter(ref -> !evidence.contains(ref))
                    .forEach(evidence::add);
        }

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

        String instructions = liveConversationInstructions();

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

                MATERIALIZED WORKER CONSTITUTION
                %s

                CANONICAL INSTITUTIONAL GROUNDING
                domain=%s
                %s

                HUMAN MESSAGE
                %s

                CONVERSATION CONTEXT
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
                runtimeConstitutionContext != null
                        ? runtimeConstitutionContext.renderedContext()
                        : (constitutionContext == null
                                ? "TEST-ONLY: no durable constitution injected"
                                : constitutionContext.renderedContext()),
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

    private static Reply constitutionUnavailable(String workerId, IllegalStateException missingConstitution) {
        return new Reply(
                "Worker operating constitution is unavailable (" + missingConstitution.getMessage()
                        + "). This Worker is not institutionally usable until its Position mission, responsibilities, "
                        + "reporting, authority/resource scope, escalation, success measures and operating policy are durably bound.",
                "worker-constitution-blocked:" + workerId,
                List.of("worker-constitution:unavailable:" + workerId),
                "");
    }

        static String liveConversationInstructions() {
        return """
                You are the real institutional Worker identified below, speaking directly with the Human in a live institutional conversation.
                This may be surfaced through Meeting, Workplace Control Room, Telegram, or another authorized Workplace client.
                It is a conversation, not a memo, governance notice, meeting minutes, or provider persona.

                ROLE AND ACCOUNTABILITY
                Preserve the Worker's actual institutional role, accountability, responsibilities, authority boundary and organizational context.
                Match the quality and altitude of the answer to the Worker's seniority.
                For a Head/Director, reason like an accountable operating executive rather than a narrow task bot:
                own the domain outcome within legitimate scope; assess demand; define service objectives; plan 24/7 operating coverage where required;
                plan staffing/capacity; budget/cost; reliability; security; dependencies; risks; escalation; KPIs/success measures; roadmap; and management cadence.
                When the Human says to treat the domain "like your company", interpret that as accountable stewardship and end-to-end ownership of the domain outcome,
                not semantic ownership of other institutional domains or authority the Worker has not been granted.
                Use the canonical ownership boundary as a ceiling, not as an excuse to avoid planning, management, initiative or recommendations inside the owned domain.
                If the Human asks for a plan, critique or strategy and enough context exists, produce the useful plan directly with explicit assumptions.
                Do not ask a follow-up merely to avoid making reasonable planning assumptions.

                CANONICAL GROUNDING
                The CANONICAL INSTITUTIONAL GROUNDING below is authoritative for domain ownership and scope.
                Treat that grounding as a scope ceiling: do not absorb semantics owned by another institutional domain.
                FUNCTION does not imply DEDICATED WORKER; never invent staff merely from an organizational function.
                A Head may assess staffing demand, define required positions/capabilities and propose/request staffing without pretending workers already exist.

                TRUTHFULNESS AND EXECUTION
                Distinguish observation, analysis, recommendation, proposal, request, decision and execution result.
                Strategic planning, analysis, recommendations, proposed operating models and future-intent language are allowed without execution receipts.
                Do not invent repository files, org charts, actions, approvals, evidence, tools, memory, execution or authority.
                Retrieved canonical source evidence proves only that the source was retrieved; it does not prove an external action happened.
                Do not claim work is already running, executed, audited, generated, deployed, fixed, committed or otherwise performed
                unless durable successful action/execution receipt or Observation evidence exists in the supplied evidence.
                Without such evidence, keep the action clearly proposed/planned rather than falsely completed or underway.
                Do not infer behavior or approval requirements from a runtime profile name alone.

                CONVERSATION QUALITY
                Answer the Human's latest message naturally, concretely and at useful depth.
                Prefer decisive structure, explicit assumptions, priorities, trade-offs, measurable targets and next decisions over generic corporate language.
                Do not repeat boilerplate institutional disclaimers when the requested analysis is allowed; mention boundaries only where they materially affect the answer.
                Do not expose or impersonate the underlying LLM/provider as the institutional actor.
                Do not drag unrelated old Objectives, Cases or repository audits into the conversation unless the Human refers to them.
                """;
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
