package com.metatron.workforce.management;

import com.metatron.workforce.action.ActionFabric;
import com.metatron.workforce.action.ActionJournal;
import com.metatron.workforce.action.CognitiveWorkerRuntime;
import com.metatron.workforce.action.GeneralCognitiveWorkerBrain;
import com.metatron.workforce.action.GeneralCognitiveWorkerBrainFactory;
import com.metatron.workforce.action.GeneralWorkspaceActionCatalog;
import com.metatron.workforce.execution.governance.ExecutionGate;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.runtime.ObjectiveWorkspaceService;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Head of Aquaculture planning work: read the BIOS Aquaculture governance inputs one file at a time, write
 * ACTION_PLAN_vN per EXECUTION_PLAN §7 and propose it as an unmerged pull request to kelvinka38/bios.
 *
 * <p>Reuses the canonical Worker actor path unchanged — {@link GeneralWorkspaceActionCatalog} (filtered by the
 * DOMAIN_HEAD profile and the HOA resource scope), {@link GeneralCognitiveWorkerBrain} and
 * {@link CognitiveWorkerRuntime}. It adds one governed precondition (each required input is read with its own
 * workspace.file.read before anything else) and a deterministic post-check: success is reported only when every
 * required input was read, the plan carries all seven §7 parts and an unmerged PR was published.</p>
 */
@Component
public final class AquacultureDomainPlanningCapability implements AutonomousExecutionCapability {
    public static final String CAPABILITY = "aquaculture.domain.planning";
    public static final String AUTHORITY_REFERENCE = "policy:founder-aquaculture-domain-planning:v1";
    public static final String AUTHORIZATION_REFERENCE = "authorization:founder-aquaculture-domain-planning:v1";
    public static final String REPOSITORY = "kelvinka38/bios";
    public static final String ACTION_PLAN_V1_PATH = "DOMAINS/AQUACULTURE/ACTION_PLANS/ACTION_PLAN_v1.md";
    /** EXECUTION_PLAN §7: the seven parts every ACTION_PLAN_vN must contain, used as its section headings. */
    public static final List<String> SECTION_7_PARTS = List.of(
            "Ưu tiên",
            "Chia task",
            "Phân worker và năng lực",
            "Thứ tự và phụ thuộc",
            "Output kỳ vọng",
            "Điểm review",
            "Retry và leo thang");
    static final List<String> FIXED_LEADING_INPUTS = List.of(
            "DOMAINS/AQUACULTURE/EXECUTION_PLAN.md",
            "DOMAINS/AQUACULTURE/DECISIONS.md");
    static final String DOMAIN_PACK_DIRECTORY = "DOMAINS/AQUACULTURE/DOMAIN_PACK";
    static final List<String> FIXED_TRAILING_INPUTS = List.of(
            "KNOWLEDGE/DOMAINS/AQUACULTURE/v2/gaps.yaml",
            "KNOWLEDGE/DOMAINS/AQUACULTURE/v2/COVERAGE.md");
    private static final int MAX_COGNITIVE_CYCLES = 48;
    private static final Pattern PROVIDER_MODEL = Pattern.compile("provider=([^;]+);model=([^;]+)");
    private static final Pattern LEGACY_PROVIDER_MODEL = Pattern.compile("^worker-intelligence-provider:([^:]+):model=([^:]+)");

    private final GeneralWorkspaceActionCatalog actions;
    private final GeneralCognitiveWorkerBrainFactory brains;
    private final WorkerRuntimeProfileBindingService profiles;
    private final ObjectiveWorkspaceService workspaces;
    private final ExecutionGate executionGate;

    @Autowired
    public AquacultureDomainPlanningCapability(GeneralWorkspaceActionCatalog actions,
                                               GeneralCognitiveWorkerBrainFactory brains,
                                               WorkerRuntimeProfileBindingService profiles,
                                               ObjectiveWorkspaceService workspaces,
                                               ExecutionGate executionGate) {
        this.actions = Objects.requireNonNull(actions, "actions");
        this.brains = Objects.requireNonNull(brains, "brains");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.executionGate = Objects.requireNonNull(executionGate, "executionGate");
    }

    @Override public String capabilityRef() { return CAPABILITY; }
    @Override public String capabilityDescription() {
        return CAPABILITY + " — Head of Aquaculture reads BIOS governance inputs and proposes ACTION_PLAN_vN as an unmerged PR";
    }
    @Override public String authorityReference() { return AUTHORITY_REFERENCE; }
    @Override public String authorizationReference() { return AUTHORIZATION_REFERENCE; }
    @Override public double minimumCapabilityLevel() { return 1.0; }
    @Override public double requiredCapacity() { return 1.0; }
    @Override public boolean supportsWorker(String workerId) { return AquacultureHeadAppointmentCapability.WORKER_ID.equals(workerId); }

    /**
     * The governed Work step for an ACTION_PLAN_v1 Objective. The brain-visible Work text deliberately names no
     * repository file path (GeneralCognitiveWorkerBrain derives a forced git-add target from the first path in
     * Work text); the Founder Objective and every path travel in governed memory instead.
     */
    public static ExecutionWorkSpec actionPlanWork(String stepId, String founderObjective) {
        Objects.requireNonNull(founderObjective, "founderObjective");
        return new ExecutionWorkSpec(
                stepId,
                "Head of Aquaculture: prepare ACTION_PLAN_v1 for BIOS Aquaculture as the Founder Objective in memory asks. "
                        + "Read every required governance input individually, write the plan with the seven required "
                        + "section headings at the deliverable path in memory, and publish it as an unmerged GitHub pull "
                        + "request for review.",
                REPOSITORY,
                CAPABILITY,
                List.of(),
                ExecutionWorkSpec.Consequence.MUTATING,
                List.of("every required governance input read individually",
                        "plan contains all seven required sections",
                        "unmerged pull request opened with only allowed paths"),
                List.of("hoa-input-read per required input", "hoa-model-identity", "hoa-pr-url"),
                null);
    }

    @Override
    public CapabilityResult execute(CapabilityRequest request) {
        Objects.requireNonNull(request, "request");
        String workerId = request.allocatedWorkerId();
        if (!request.allocated()) throw new SecurityException("governed allocation required for aquaculture planning");
        if (!AquacultureHeadAppointmentCapability.WORKER_ID.equals(workerId)) {
            throw new SecurityException("aquaculture planning worker mismatch");
        }
        if (!AUTHORIZATION_REFERENCE.equals(request.authorizationReference())) {
            throw new SecurityException("aquaculture planning authorization mismatch");
        }
        if (request.workSpec().consequence() == ExecutionWorkSpec.Consequence.MUTATING && !request.governanceBound()) {
            throw new SecurityException("sot-governance-binding-required-for-mutating-aquaculture-planning");
        }
        String target = request.workSpec().target().trim();
        if (target.startsWith("repository:")) target = target.substring("repository:".length());
        if (!REPOSITORY.equalsIgnoreCase(target)) {
            throw new SecurityException("aquaculture planning is limited to " + REPOSITORY + ": target=" + request.workSpec().target());
        }
        WorkerRuntimeProfileBindingService.Binding binding = profiles.requireBinding(workerId);
        if (!WorkerRuntimeProfileBindingService.DOMAIN_HEAD_PROFILE.equals(binding.profile().profileRef())) {
            throw new SecurityException("aquaculture planning requires the domain-head runtime profile");
        }

        ExecutionWorkSpec work = brainSafeWork(request.workSpec());
        ObjectiveWorkspaceService.ObjectiveWorkspace workspace = workspaces.provision(request.objectiveId(), workerId);
        Map<String, String> memory = new LinkedHashMap<>(
                GeneralWorkspaceAutonomousCapability.objectiveWorkspaceMemory(workspaces, workspace));
        memory.put("founderObjective", request.workSpec().objective());
        memory.put("deliverablePath", ACTION_PLAN_V1_PATH);
        memory.put("actionPlanRequiredSections", String.join(" | ", SECTION_7_PARTS));
        memory.put("actionPlanFormat", "Markdown; one '## <section>' heading per required section, in the listed order");
        memory.put("writePathPrefixes", String.join(",", AquacultureHeadStaffingPolicy.WRITE_PATH_PREFIXES));
        memory.put("hoaRules", "no number without an opened source; founder assumptions are DECLARED; never claim "
                + "execution without evidence; propose only, never merge or deploy");

        ActionFabric fabric = new ActionFabric(
                actions.actions(workerId, request.authorizationReference(), request.objectiveId()), executionGate);
        GeneralCognitiveWorkerBrain brain = brains.create();
        CognitiveWorkerRuntime.Brain governed = requiredInputsFirst(
                GeneralWorkspaceAutonomousCapability.withObjectiveWorkspaceMemory(brain, memory), workspace);
        ActionJournal journal = ActionJournal.runtimeEvidenceJournal();
        CognitiveWorkerRuntime.Outcome outcome = new CognitiveWorkerRuntime(
                fabric, journal, MAX_COGNITIVE_CYCLES, executionGate).execute(
                workerId, request.assignmentReference(), request.authorizationReference(), request.objectiveId(),
                work, request.idempotencyKey(), request.governanceContext(), governed);

        List<String> evidence = new ArrayList<>(new LinkedHashSet<>(journal.objectiveEvidenceReferences(request.objectiveId())));
        outcome.evidenceReferences().stream().filter(ref -> !evidence.contains(ref)).forEach(evidence::add);
        List<String> problems = new ArrayList<>();

        List<String> required = requiredInputs(workspace);
        Set<String> read = successfulReads(outcome.cycles());
        for (String path : required) {
            if (read.contains(path)) evidence.add("hoa-input-read:" + path);
            else problems.add("hoa-input-not-read:" + path);
        }

        Set<String> models = modelIdentities(brain.evidenceReferences());
        if (models.isEmpty()) problems.add("hoa-model-identity-missing");
        models.forEach(identity -> evidence.add("hoa-model-identity:" + identity));

        Path plan = workspaces.resolve(workspace, ACTION_PLAN_V1_PATH);
        if (!Files.isRegularFile(plan, LinkOption.NOFOLLOW_LINKS)) {
            problems.add("hoa-action-plan-missing:" + ACTION_PLAN_V1_PATH);
        } else {
            String content = workspaces.read(workspace, ACTION_PLAN_V1_PATH).toLowerCase(Locale.ROOT);
            for (String part : SECTION_7_PARTS) {
                if (!content.contains("## " + part.toLowerCase(Locale.ROOT))) problems.add("hoa-action-plan-section-missing:" + part);
            }
            evidence.add("hoa-action-plan:" + ACTION_PLAN_V1_PATH);
        }

        CognitiveWorkerRuntime.Cycle publication = outcome.cycles().stream()
                .filter(cycle -> "workspace.github.pr.publish".equals(cycle.thought().actionRef()))
                .filter(cycle -> cycle.observation().success())
                .reduce((first, second) -> second).orElse(null);
        if (publication == null) {
            problems.add("hoa-pr-not-published");
        } else {
            evidence.add("hoa-pr-url:" + publication.observation().outputs().getOrDefault("pullRequestUrl", ""));
            evidence.add("github-merge-performed:false");
        }
        evidence.add("hoa-runtime-profile:" + binding.profile().profileRef());
        evidence.add("hoa-action-catalog:" + fabric.actionRefs().stream().sorted().toList());
        if (request.governanceContext() != null) {
            evidence.add("governance-plan:" + request.governanceContext().planId() + "@" + request.governanceContext().planVersion());
            evidence.add("governance-attempt:" + request.governanceContext().attemptId()
                    + ":fence=" + request.governanceContext().fencingToken());
        }
        problems.forEach(evidence::add);

        boolean success = outcome.success() && problems.isEmpty();
        String summary = success
                ? "ACTION_PLAN_v1 proposed as unmerged PR after reading " + required.size() + " governance inputs"
                : "ACTION_PLAN_v1 not accepted: " + (outcome.success() ? String.join(", ", problems) : outcome.summary());
        return new CapabilityResult(success, workerId, request.assignmentReference(), workspace.workspaceRef(),
                List.copyOf(evidence), summary);
    }

    /** Keeps the step identity/target/consequence the governance plan bound, with brain-safe Work text. */
    static ExecutionWorkSpec brainSafeWork(ExecutionWorkSpec bound) {
        ExecutionWorkSpec safe = actionPlanWork(bound.stepId(), bound.objective());
        return new ExecutionWorkSpec(bound.stepId(), safe.objective(), bound.target(), bound.requiredCapability(),
                bound.dependsOn(), bound.consequence(), safe.acceptanceCriteria(), safe.evidenceRequirements(),
                bound.completionPolicy());
    }

    /** Required inputs in reading order; DOMAIN_PACK/* expands to the files actually present in the checkout. */
    List<String> requiredInputs(ObjectiveWorkspaceService.ObjectiveWorkspace workspace) {
        List<String> required = new ArrayList<>(FIXED_LEADING_INPUTS);
        try {
            for (String file : workspaces.list(workspace, DOMAIN_PACK_DIRECTORY)) {
                String path = file.startsWith(DOMAIN_PACK_DIRECTORY + "/") ? file : DOMAIN_PACK_DIRECTORY + "/" + file;
                if (path.endsWith(".md") && !required.contains(path)) required.add(path);
            }
        } catch (RuntimeException absent) {
            required.add(DOMAIN_PACK_DIRECTORY + "/README.md");
        }
        required.subList(FIXED_LEADING_INPUTS.size(), required.size()).sort(String::compareTo);
        required.addAll(FIXED_TRAILING_INPUTS);
        return List.copyOf(required);
    }

    private CognitiveWorkerRuntime.Brain requiredInputsFirst(CognitiveWorkerRuntime.Brain delegate,
                                                             ObjectiveWorkspaceService.ObjectiveWorkspace workspace) {
        return new CognitiveWorkerRuntime.Brain() {
            @Override public CognitiveWorkerRuntime.Thought think(CognitiveWorkerRuntime.CognitiveContext context) {
                if (!materialized(context)) return delegate.think(context);
                List<String> required = requiredInputs(workspace);
                for (String path : required) {
                    if (!readAttempted(context.history(), path)) {
                        return new CognitiveWorkerRuntime.Thought("workspace.file.read", Map.of("path", path),
                                "Governed HOA precondition: read each required governance input individually before planning");
                    }
                }
                return delegate.think(withRequiredInputs(context, required));
            }
            @Override public CognitiveWorkerRuntime.Reflection reflect(CognitiveWorkerRuntime.CognitiveContext context,
                                                                        ActionFabric.ActionObservation observation) {
                return delegate.reflect(context, observation);
            }
            @Override public boolean blocksCompletionForUnresolvedFailure(CognitiveWorkerRuntime.CognitiveContext context,
                                                                           String actionRef) {
                return delegate.blocksCompletionForUnresolvedFailure(context, actionRef);
            }
        };
    }

    private static CognitiveWorkerRuntime.CognitiveContext withRequiredInputs(CognitiveWorkerRuntime.CognitiveContext context,
                                                                             List<String> required) {
        Map<String, String> memory = new LinkedHashMap<>(context.memory());
        memory.put("requiredGovernanceInputs", String.join(";", required));
        return new CognitiveWorkerRuntime.CognitiveContext(context.workerId(), context.assignmentReference(),
                context.authorizationReference(), context.objectiveId(), context.workSpec(), context.idempotencyKey(),
                context.availableActions(), context.history(), Map.copyOf(memory));
    }

    private static boolean materialized(CognitiveWorkerRuntime.CognitiveContext context) {
        if ("true".equalsIgnoreCase(context.memory().getOrDefault(GeneralWorkspaceAutonomousCapability.MEMORY_WORKSPACE_MATERIALIZED, "false"))) {
            return true;
        }
        return context.history().stream().anyMatch(cycle ->
                "workspace.repository.materialize".equals(cycle.thought().actionRef()) && cycle.observation().success());
    }

    private static boolean readAttempted(List<CognitiveWorkerRuntime.Cycle> history, String path) {
        return history.stream().anyMatch(cycle -> "workspace.file.read".equals(cycle.thought().actionRef())
                && path.equals(cycle.thought().inputs().get("path")));
    }

    private static Set<String> successfulReads(List<CognitiveWorkerRuntime.Cycle> cycles) {
        Set<String> read = new LinkedHashSet<>();
        for (CognitiveWorkerRuntime.Cycle cycle : cycles) {
            if ("workspace.file.read".equals(cycle.thought().actionRef()) && cycle.observation().success()) {
                String path = cycle.thought().inputs().get("path");
                if (path != null) read.add(path);
            }
        }
        return read;
    }

    static Set<String> modelIdentities(List<String> brainEvidence) {
        Set<String> identities = new LinkedHashSet<>();
        for (String ref : brainEvidence) {
            Matcher current = PROVIDER_MODEL.matcher(ref);
            if (ref.startsWith("worker-cognition-evidence") && current.find()) {
                identities.add("provider=" + current.group(1).trim() + ";model=" + current.group(2).trim());
                continue;
            }
            Matcher legacy = LEGACY_PROVIDER_MODEL.matcher(ref);
            if (legacy.find()) identities.add("provider=" + legacy.group(1).trim() + ";model=" + legacy.group(2).trim());
        }
        return identities;
    }
}
