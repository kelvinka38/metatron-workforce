package com.metatron.workforce.management;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.interaction.FounderDefinedWorkerFormationService;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.interaction.intelligence.WorkerIntelligenceService;
import com.metatron.workforce.operating.WorkerConstitutionService;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executable cognitive-work adapter for Founder-defined Workers.
 *
 * <p>This is deliberately non-effecting: it may reason and persist an institutional work product,
 * but it cannot self-grant shell/Git/deploy or any other external mutation. Workforce allocation
 * still creates a real Assignment and Execution attempt around this adapter.</p>
 */
@Component
public final class FounderDefinedCognitiveWorkCapability implements AutonomousExecutionCapability {
    public static final String AUTHORIZATION_REFERENCE = "authorization:founder-defined-cognitive-work:v1";
    private static final Pattern WORKER_REF = Pattern.compile("(?i)\\bWORKER-[A-Z0-9._:-]+\\b");

    private final WorkforceCoreService core;
    private final WorkerRuntimeProfileBindingService runtimeProfiles;
    private final WorkerConstitutionService constitution;
    private final WorkerIntelligenceService intelligence;
    private final FounderWorkerWorkProductStore products;

    public FounderDefinedCognitiveWorkCapability(
            WorkforceCoreService core,
            WorkerRuntimeProfileBindingService runtimeProfiles,
            WorkerConstitutionService constitution,
            WorkerIntelligenceService intelligence,
            FounderWorkerWorkProductStore products) {
        this.core = Objects.requireNonNull(core, "core");
        this.runtimeProfiles = Objects.requireNonNull(runtimeProfiles, "runtimeProfiles");
        this.constitution = Objects.requireNonNull(constitution, "constitution");
        this.intelligence = Objects.requireNonNull(intelligence, "intelligence");
        this.products = Objects.requireNonNull(products, "products");
    }

    @Override public String capabilityRef() { return FounderDefinedWorkerFormationService.COGNITIVE_CAPABILITY; }
    @Override public String capabilityDescription() {
        return capabilityRef() + " — canonical Founder-defined Worker cognitive work with durable work product";
    }
    @Override public String authorityReference() { return FounderDefinedWorkerFormationService.AUTHORITY_REF; }
    @Override public String authorizationReference() { return AUTHORIZATION_REFERENCE; }
    @Override public double minimumCapabilityLevel() { return 1.0; }
    @Override public double requiredCapacity() { return 1.0; }

    @Override
    public boolean supportsWorker(String workerId) {
        if (workerId == null || workerId.isBlank()) return false;
        try {
            WorkforceCoreService.Worker worker = core.worker(workerId);
            if (worker.status() != WorkforceCoreService.WorkerStatus.ACTIVE) return false;
            WorkerRuntimeProfileBindingService.Binding binding = runtimeProfiles.requireBinding(workerId);
            if (!FounderDefinedWorkerFormationService.RUNTIME_PROFILE.equals(binding.profile().profileRef())) return false;
            return core.capabilities(workerId).stream().anyMatch(capability ->
                    capability.capabilityRef().equals(capabilityRef()) && capability.level() >= minimumCapabilityLevel());
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    @Override
    public boolean supportsWorker(String workerId, ExecutionWorkSpec workSpec) {
        if (!supportsWorker(workerId)) return false;
        String explicitlyTargeted = explicitWorkerTarget(workSpec);
        return explicitlyTargeted.isBlank() || workerId.equalsIgnoreCase(explicitlyTargeted);
    }

    @Override
    public CapabilityResult execute(CapabilityRequest request) {
        Objects.requireNonNull(request, "request");
        if (!request.allocated()) throw new SecurityException("governed allocation required for Founder-defined cognitive work");
        if (!AUTHORIZATION_REFERENCE.equals(request.authorizationReference())) {
            throw new SecurityException("founder cognitive-work authorization mismatch");
        }
        if (request.workSpec().consequence() != ExecutionWorkSpec.Consequence.READ_ONLY) {
            throw new SecurityException("Founder-defined cognitive work cannot perform external mutation");
        }
        if (!capabilityRef().equals(request.workSpec().requiredCapability())) {
            throw new SecurityException("Founder-defined cognitive-work capability mismatch");
        }
        if (!supportsWorker(request.allocatedWorkerId(), request.workSpec())) {
            throw new SecurityException("allocated Worker does not match Founder-defined cognitive-work target");
        }

        WorkforceCoreService.Participation participation = core.participations(request.allocatedWorkerId()).stream()
                .filter(value -> value.status() == WorkforceCoreService.ParticipationStatus.ACTIVE)
                .sorted(java.util.Comparator.comparing(WorkforceCoreService.Participation::participationId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Founder-defined Worker has no active participation"));
        WorkerConstitutionService.ConstitutionContext workerContext =
                constitution.contextFor(request.allocatedWorkerId(), participation.participationId());

        List<String> evidence = new ArrayList<>(workerContext.evidenceReferences());
        evidence.add("worker:" + request.allocatedWorkerId() + ":status=ACTIVE");
        evidence.add("worker-assignment:" + request.assignmentReference());
        evidence.add("worker-objective:" + request.objectiveId());
        evidence.add("worker-capability:" + capabilityRef());
        evidence.add("worker-runtime-profile:" + FounderDefinedWorkerFormationService.RUNTIME_PROFILE);

        String instructions = """
                Produce the requested institutional cognitive work as the canonical Worker described below.
                Return the useful work product itself, not a promise to do it later.
                Stay within the Worker's durable Position mission, responsibilities and decision rights.
                This capability is cognitive-only: do not claim shell, Git, deployment, publication, external messaging,
                or any other external effect occurred. If the request needs such an effect, clearly identify that boundary.
                Satisfy the supplied observable acceptance criteria as far as cognitive work can legitimately do so.
                """;
        String context = """
                ASSIGNED WORK
                objective_id=%s
                assignment_reference=%s
                step_id=%s
                objective=%s
                target=%s
                acceptance_criteria=%s
                evidence_requirements=%s

                CANONICAL WORKER CONSTITUTION
                %s
                """.formatted(
                request.objectiveId(), request.assignmentReference(), request.workSpec().stepId(),
                request.workSpec().objective(), request.workSpec().target(),
                request.workSpec().acceptanceCriteria(), request.workSpec().evidenceRequirements(),
                workerContext.renderedContext());

        WorkerIntelligenceService.Response response = intelligence.reason(new WorkerIntelligenceService.Request(
                request.allocatedWorkerId(), capabilityRef(), instructions, context, List.copyOf(evidence)));
        evidence.addAll(response.evidenceReferences());
        evidence.add("worker-cognitive-request:" + response.requestReference());

        String productId = "work-product:" + request.allocatedWorkerId().toLowerCase(Locale.ROOT)
                + ":" + Integer.toUnsignedString(Objects.hash(
                request.objectiveId(), request.assignmentReference(), request.workSpec().stepId()), 16);
        FounderWorkerWorkProductStore.WorkProduct product = products.save(new FounderWorkerWorkProductStore.WorkProduct(
                productId,
                request.objectiveId(),
                request.assignmentReference(),
                request.allocatedWorkerId(),
                participation.participationId(),
                participation.roleRef(),
                request.workSpec().objective(),
                response.text(),
                List.copyOf(evidence),
                Instant.now()));
        evidence.add("founder-worker-work-product:" + product.productId());

        return new CapabilityResult(
                true,
                request.allocatedWorkerId(),
                request.assignmentReference(),
                product.productId(),
                List.copyOf(evidence),
                response.text());
    }

    static String explicitWorkerTarget(ExecutionWorkSpec workSpec) {
        if (workSpec == null) return "";
        for (String candidate : List.of(workSpec.target(), workSpec.objective())) {
            Matcher matcher = WORKER_REF.matcher(candidate == null ? "" : candidate);
            if (matcher.find()) return matcher.group().toUpperCase(Locale.ROOT);
        }
        return "";
    }
}
