package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.FounderDefinedWorkerFormationService;
import com.metatron.workforce.management.GeneralWorkspaceAutonomousCapability;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic planning bridge for explicit work addressed to one canonical Founder-defined Worker.
 * It does not create Workers or authority; it only binds an already explicit Worker target to the
 * shared governed cognitive-work capability so Workforce can create the real Assignment/Execution.
 */
public final class FounderWorkerExecutionPlanProposalService implements ExecutionPlanProposalService {
    private static final Pattern WORKER_REF = Pattern.compile("(?i)\\bWORKER-[A-Z0-9._:-]+\\b");
    private final ExecutionPlanProposalService delegate;

    public FounderWorkerExecutionPlanProposalService(ExecutionPlanProposalService delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public List<ExecutionWorkSpec> propose(
            String caseId,
            NormalizedRequest request,
            List<String> availableExecutionCapabilities) {
        List<ExecutionWorkSpec> deterministic = explicitCanonicalGeneralEngineeringWork(request, availableExecutionCapabilities);
        if (deterministic.isEmpty()) deterministic = explicitFounderWorkerWork(request, availableExecutionCapabilities);
        if (!deterministic.isEmpty() && request.explicitlyRequestedProvider() == null) return deterministic;
        return delegate.propose(caseId, request, availableExecutionCapabilities);
    }

    /**
     * Root-cause fix (2026-09-16, found live in production): explicitFounderWorkerWork() below binds
     * ANY explicit "WORKER-*" text reference to the generic Founder-defined cognitive-work shortcut
     * (worker.cognition.work, READ_ONLY) -- correct for a generic Founder-defined cognitive-only Worker
     * like WORKER-COMPOSER-ARTIST, but WRONG for the canonical WORKER-GENERAL-ENGINEERING, which already
     * has its own governed execution capability (execution.general.workspace) and staffing policy
     * (GeneralEngineeringStaffingPolicy). Routing a real engineering-implementation Objective through
     * the cognitive shortcut downgraded it to a READ_ONLY cognitive-work step and then blocked with
     * capacity-unavailable:worker.cognitive.work -- a capability General Engineering was never staffed
     * for and should never need, since it already has its own real execution path.
     *
     * This checks for that one specific, already-governed canonical identity before the generic
     * shortcut ever runs. It is deliberately not a broad heuristic: only the literal
     * GeneralWorkspaceAutonomousCapability.WORKER_ID is special-cased, resolved against its own real
     * capability constant, not a role-name pattern -- an arbitrary Founder-defined Worker whose name
     * merely sounds technical still falls through to the unchanged generic path below.
     *
     * Consequence is not hardcoded MUTATING: it is classified from the objective text by
     * GeneralWorkspaceAutonomousCapability.classifyConsequence(), the same General Workspace capability
     * that owns execution.general.workspace semantics, so a genuinely read-only General Engineering
     * inspection request (inspect/review/analyze/explain) stays READ_ONLY instead of being forced into
     * MUTATING governance it does not need.
     *
     * Authority target semantics (2026-09-17, found live in production): the resulting WorkSpec's
     * target() is a governed SoT resource -- GovernancePlanService.bindAuthorizedWork() feeds it
     * straight into SotDiscoveryService.discover() to resolve an authority manifest -- never the
     * performer identity. Performer identity (who executes) already flows entirely separately, through
     * GeneralWorkspaceAutonomousCapability.supportsWorker() and AutonomousStaffingService; it never reads
     * ExecutionWorkSpec.target(). Binding target() to the literal Worker id therefore both abuses the
     * field and can never resolve, since no authority manifest is (or should be) keyed by a Worker
     * identity. governedRepositoryTarget() resolves the real governed resource instead: a bare
     * "owner/repo" Founder repository, exactly the shape GeneralCognitiveWorkerBrain already parses out
     * of target() to materialize its workspace baseline, and exactly the shape already carrying real,
     * Founder-ratified authority in sot-enforcement-authority-manifests.json -- an explicitly named
     * repository in the Objective when there is one, otherwise the canonical Workforce repository itself
     * for a brand-new app Objective that names no repository yet.
     */
    static List<ExecutionWorkSpec> explicitCanonicalGeneralEngineeringWork(
            NormalizedRequest request,
            List<String> availableExecutionCapabilities) {
        if (request == null || request.mode() != IntelligenceMode.EXECUTION) return List.of();
        boolean available = availableExecutionCapabilities != null && availableExecutionCapabilities.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .anyMatch(GeneralWorkspaceAutonomousCapability.CAPABILITY::equals);
        if (!available) return List.of();

        String workerId = workerRef(request.target());
        if (workerId.isBlank()) workerId = workerRef(request.objective());
        if (!GeneralWorkspaceAutonomousCapability.WORKER_ID.equals(workerId)) return List.of();

        String objective = request.objective().trim();
        if (objective.isBlank()) return List.of();
        String governedTarget = governedRepositoryTarget(objective);
        return List.of(new ExecutionWorkSpec(
                "general-engineering-workspace-execution",
                objective,
                governedTarget,
                GeneralWorkspaceAutonomousCapability.CAPABILITY,
                List.of(),
                GeneralWorkspaceAutonomousCapability.classifyConsequence(objective),
                List.of(
                        "canonical Worker " + workerId + " performs the requested workspace execution",
                        "the work is executed under a real Workforce Assignment attributed to " + workerId
                                + " through its governed general workspace capability"),
                List.of(
                        "general-workspace-execution durable work product/evidence",
                        "worker-assignment evidence attributed to " + workerId)));
    }

    /**
     * Governed repository target for General Workspace work: an explicit repository named in the
     * Objective text, or -- for an Objective that names none, such as a brand-new app with nothing to
     * check out yet -- the canonical Workforce repository. Never the Worker identity.
     */
    static String governedRepositoryTarget(String objective) {
        String repositories = CanonicalObjectiveControlInterpreter.repositoryTargets(objective);
        String first = repositories.isBlank() ? "" : repositories.split(",")[0].trim();
        return first.isBlank() ? GeneralWorkspaceAutonomousCapability.DEFAULT_GOVERNED_REPOSITORY : first;
    }

    static List<ExecutionWorkSpec> explicitFounderWorkerWork(
            NormalizedRequest request,
            List<String> availableExecutionCapabilities) {
        if (request == null || request.mode() != IntelligenceMode.EXECUTION) return List.of();
        boolean available = availableExecutionCapabilities != null && availableExecutionCapabilities.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .anyMatch(FounderDefinedWorkerFormationService.COGNITIVE_CAPABILITY::equals);
        if (!available) return List.of();

        String workerId = workerRef(request.target());
        if (workerId.isBlank()) workerId = workerRef(request.objective());
        if (workerId.isBlank()) return List.of();
        if (GeneralWorkspaceAutonomousCapability.WORKER_ID.equals(workerId)) return List.of();

        String objective = request.objective().trim();
        if (objective.isBlank()) return List.of();
        return List.of(new ExecutionWorkSpec(
                "founder-worker-cognitive-work",
                objective,
                workerId,
                FounderDefinedWorkerFormationService.COGNITIVE_CAPABILITY,
                List.of(),
                ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of(
                        "canonical Worker " + workerId + " produces the requested cognitive work product",
                        "the work is executed under a real Workforce Assignment attributed to " + workerId),
                List.of(
                        "founder-worker-work-product durable cognitive work product",
                        "worker-assignment evidence attributed to " + workerId,
                        "worker-cognitive-request evidence")));
    }

    static String workerRef(String value) {
        Matcher matcher = WORKER_REF.matcher(value == null ? "" : value);
        return matcher.find() ? matcher.group().toUpperCase(Locale.ROOT) : "";
    }
}
