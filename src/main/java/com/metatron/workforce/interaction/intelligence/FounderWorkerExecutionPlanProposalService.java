package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.FounderDefinedWorkerFormationService;
import com.metatron.workforce.management.GeneralWorkspaceAutonomousCapability;
import com.metatron.workforce.operating.PositionAddressResolver;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic planning bridge for explicit work addressed to one canonical Founder-defined Worker.
 * It does not create Workers or authority; it only binds an already explicit Worker target to the
 * shared governed cognitive-work capability so Workforce can create the real Assignment/Execution.
 */
public final class FounderWorkerExecutionPlanProposalService implements ExecutionPlanProposalService {
    private static final Pattern WORKER_REF = Pattern.compile("(?i)\\bWORKER-[A-Z0-9._:-]+\\b");
    private static final String ROLE_REF_PREFIX = "ROLE-";
    private final ExecutionPlanProposalService delegate;
    private final PositionAddressResolver addresses;
    private final Map<String, PositionWorkRoute> routes = new LinkedHashMap<>();

    public FounderWorkerExecutionPlanProposalService(ExecutionPlanProposalService delegate) {
        this(delegate, PositionAddressResolver.none(), List.of());
    }

    public FounderWorkerExecutionPlanProposalService(
            ExecutionPlanProposalService delegate,
            PositionAddressResolver addresses,
            List<? extends PositionWorkRoute> routes) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.addresses = Objects.requireNonNull(addresses, "addresses");
        for (PositionWorkRoute route : Objects.requireNonNull(routes, "routes")) {
            if (this.routes.putIfAbsent(route.capability(), route) != null) {
                throw new IllegalStateException("duplicate position work route: " + route.capability());
            }
        }
    }

    @Override
    public List<ExecutionWorkSpec> propose(
            String caseId,
            NormalizedRequest request,
            List<String> availableExecutionCapabilities) {
        List<ExecutionWorkSpec> deterministic = explicitCanonicalGeneralEngineeringWork(request, availableExecutionCapabilities);
        if (deterministic.isEmpty()) deterministic = positionAddressedWork(request, availableExecutionCapabilities);
        if (deterministic.isEmpty() && !namesRoutedPosition(request)) {
            deterministic = explicitFounderWorkerWork(request, availableExecutionCapabilities);
        }
        if (!deterministic.isEmpty() && request.explicitlyRequestedProvider() == null) return deterministic;
        return singleRoutedStep(request, delegate.propose(caseId, request, availableExecutionCapabilities));
    }

    /**
     * Work for an EXECUTION Objective addressed to an occupied Position (PR #543 production fix, generalized): the
     * addressee is whoever the Objective names explicitly (a WORKER- id, else a ROLE- target) or, only when it names
     * nobody explicitly, whoever the Objective opens by addressing through an alias that the Position declares in
     * its contract. The {@link PositionWorkRoute} of the Position's declared primaryCapability plans the one step.
     * Positions, aliases, primary capabilities and routes are declared by their owners; this planner holds none of
     * them, so a new Head needs no change here. An ambiguous alias fails as AMBIGUOUS_ADDRESS instead of being
     * guessed.
     */
    List<ExecutionWorkSpec> positionAddressedWork(
            NormalizedRequest request,
            List<String> availableExecutionCapabilities) {
        if (request == null || request.mode() != IntelligenceMode.EXECUTION) return List.of();
        String objective = request.objective() == null ? "" : request.objective().trim();
        if (objective.isBlank()) return List.of();
        return addressee(request)
                .flatMap(this::routeFor)
                .filter(route -> available(route.capability(), availableExecutionCapabilities))
                .map(route -> List.of(route.work(stepId(route), objective)))
                .orElse(List.of());
    }

    private Optional<PositionAddressResolver.Address> addressee(NormalizedRequest request) {
        String workerId = workerRef(request.target());
        if (workerId.isBlank()) workerId = workerRef(request.objective());
        if (!workerId.isBlank()) return addresses.byWorker(workerId);
        String target = request.target() == null ? "" : request.target().trim();
        if (target.toUpperCase(Locale.ROOT).startsWith(ROLE_REF_PREFIX)) return addresses.byRole(target);
        return addresses.resolveAlias(request.objective());
    }

    private Optional<PositionWorkRoute> routeFor(PositionAddressResolver.Address address) {
        return Optional.ofNullable(routes.get(address.primaryCapability()));
    }

    /**
     * An explicitly named Worker whose Position has its own routed capability is never downgraded to generic
     * cognitive work, even when that capability is momentarily unavailable (the frontier planner decides then).
     */
    private boolean namesRoutedPosition(NormalizedRequest request) {
        if (request == null || request.mode() != IntelligenceMode.EXECUTION) return false;
        String workerId = workerRef(request.target());
        if (workerId.isBlank()) workerId = workerRef(request.objective());
        return !workerId.isBlank() && addresses.byWorker(workerId).flatMap(this::routeFor).isPresent();
    }

    /**
     * A frontier plan that selects a routed capability is normalized to that capability's single step (production
     * observed a Head's planning capability split into a READ_ONLY step plus generic cognitive work); plans that
     * select no routed capability are returned unchanged.
     */
    private List<ExecutionWorkSpec> singleRoutedStep(NormalizedRequest request, List<ExecutionWorkSpec> plan) {
        if (plan == null) return plan;
        for (ExecutionWorkSpec step : plan) {
            PositionWorkRoute route = step == null ? null : routes.get(step.requiredCapability());
            if (route == null) continue;
            String objective = request == null || request.objective() == null || request.objective().isBlank()
                    ? step.objective() : request.objective().trim();
            return List.of(route.work(step.stepId(), objective));
        }
        return plan;
    }

    private static String stepId(PositionWorkRoute route) {
        return route.capability().replaceAll("[^A-Za-z0-9]+", "-");
    }

    private static boolean available(String capability, List<String> availableExecutionCapabilities) {
        return availableExecutionCapabilities != null && availableExecutionCapabilities.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .anyMatch(capability::equals);
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
     * identity.
     *
     * Target shape (2026-09-17, follow-up): governedRepositoryTarget() always emits the canonical
     * "repository:owner/repo" shape -- the exact shape AuthorityManifestCatalog's repository:* authority
     * wildcard matches by prefix -- rather than a bare "owner/repo". A bare repository string only
     * resolves for the four canonical repositories that happen to also be listed as literal patterns;
     * an arbitrary explicitly-named repository (e.g. kelvinka38/new-app) would not match the wildcard at
     * all, silently working only by coincidence for the canonical four. GeneralCognitiveWorkerBrain's
     * repositoryFromTarget()/governedStagePath() strip this prefix before recovering the bare repository
     * locator, so materialization is unaffected. No new authority manifest is added: this only makes the
     * existing wildcard reachable for any repository, canonical or not.
     *
     * No-repository policy: none of the canonical SoT documents (WORKFORCE_SOT.md, 14_EXECUTION/SOT.md,
     * SOT_ENFORCEMENT_DETAILED_GAP_CLOSURE.md, WORKFORCE_AUTHORIZATION_EXECUTION_ATTRIBUTION_ARCHITECTURE.md)
     * establish a policy of routing a brand-new, unrelated app Objective at an existing repository merely
     * because that repository already resolves authority. So an Objective naming no repository is never
     * defaulted onto kelvinka38/metatron-workforce (or any other existing repository unrelated to the
     * work). Instead the target repository is derived deterministically from the application the
     * Objective itself says it is building ("... called/named X"), under the single Founder GitHub owner
     * every canonical and future repository already lives under. If neither an explicit repository nor an
     * application name can be determined, this special case declines entirely (empty list) rather than
     * inventing a target -- the request falls through to the generic Founder-worker path and, failing
     * that, frontier replanning, exactly as it already does when its own capability is unavailable.
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
        GovernedRepository governed = governedRepositoryTarget(objective);
        if (governed.repository().isBlank()) return List.of();
        List<String> evidenceRequirements = governed.existingSourceExpected()
                ? List.of(
                        "general-workspace-execution durable work product/evidence",
                        "worker-assignment evidence attributed to " + workerId)
                : List.of(
                        "general-workspace-execution durable work product/evidence",
                        "worker-assignment evidence attributed to " + workerId,
                        // Sentinel planner-to-brain signal (mirrors the existing
                        // research-action:research.web.search evidenceRequirements convention):
                        // this repository target was derived as a NEW destination, not an existing
                        // source repository, so GeneralCognitiveWorkerBrain must not require checkout
                        // of a repository that does not exist yet. Must match
                        // GeneralCognitiveWorkerBrain.NEW_APPLICATION_WORKSPACE_EVIDENCE exactly.
                        "workspace-source:fresh-new-application");
        ExecutionWorkSpec base = new ExecutionWorkSpec(
                "general-engineering-workspace-execution",
                objective,
                "repository:" + governed.repository(),
                GeneralWorkspaceAutonomousCapability.CAPABILITY,
                List.of(),
                GeneralWorkspaceAutonomousCapability.classifyConsequence(objective),
                List.of(
                        "canonical Worker " + workerId + " performs the requested workspace execution",
                        "the work is executed under a real Workforce Assignment attributed to " + workerId
                                + " through its governed general workspace capability"),
                evidenceRequirements);
        return GeneralWorkspacePhasePlanner.phase(base);
    }

    /**
     * The optional leading/trailing quote handles a Human Objective that quotes the application name
     * (observed live: {@code called "Runtime Acceptance App"}) -- the quote characters themselves are
     * matched but not captured, so the derived slug is unaffected either way.
     */
    private static final Pattern APPLICATION_NAME = Pattern.compile(
            "(?i)\\b(?:called|named)\\s+[\"“]?([A-Za-z][A-Za-z0-9' -]{1,60}?)[\"”]?(?=[.,;:]|\\s+(?:and|to|for)\\b|$)");

    /**
     * The governed repository target and whether an existing GitHub source repository is genuinely
     * expected to be materialized (an explicitly named repository), or whether this is a governance/
     * authority-discovery destination the planner derived for a brand-new application that has no
     * existing source yet.
     *
     * <p>Package-visible (not private): {@link ExecutionWorkPlanner} reuses this exact derivation to
     * normalize a frontier-planned {@code execution.general.workspace} step's target when the planner
     * itself selected that capability without any explicit Worker naming -- the same governed
     * repository-target semantics, never a second competing algorithm.</p>
     */
    record GovernedRepository(String repository, boolean existingSourceExpected) {}

    /**
     * Governed repository target for General Workspace work: an explicit repository named in the
     * Objective text, or -- for an Objective that names none -- a repository derived from the
     * application the Objective says it is building, under the single Founder GitHub owner. Blank
     * repository when neither can be determined. Never the Worker identity, and never an unrelated
     * existing repository.
     */
    static GovernedRepository governedRepositoryTarget(String objective) {
        String repositories = CanonicalObjectiveControlInterpreter.repositoryTargets(objective);
        String explicit = repositories.isBlank() ? "" : repositories.split(",")[0].trim();
        if (!explicit.isBlank()) return new GovernedRepository(explicit, true);

        Matcher name = APPLICATION_NAME.matcher(objective);
        if (!name.find()) return new GovernedRepository("", false);
        String slug = slug(name.group(1));
        if (slug.isBlank()) return new GovernedRepository("", false);
        return new GovernedRepository(GeneralWorkspaceAutonomousCapability.FOUNDER_GITHUB_OWNER + "/" + slug, false);
    }

    private static String slug(String value) {
        String folded = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return folded.length() <= 60 ? folded : folded.substring(0, 60).replaceAll("-+$", "");
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
