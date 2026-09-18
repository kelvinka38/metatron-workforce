package com.metatron.workforce.management;

import com.metatron.workforce.action.ActionFabric;
import com.metatron.workforce.action.ActionJournal;
import com.metatron.workforce.action.CognitiveWorkerRuntime;
import com.metatron.workforce.action.GeneralCognitiveWorkerBrain;
import com.metatron.workforce.action.GeneralCognitiveWorkerBrainFactory;
import com.metatron.workforce.action.GeneralWorkspaceActionCatalog;
import com.metatron.workforce.action.GeneralWebResearchAction;
import com.metatron.workforce.execution.governance.ExecutionGate;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.interaction.intelligence.GeneralWorkspacePhasePlanner;
import com.metatron.workforce.operating.WorkerConstitutionRuntimeMaterializer;
import com.metatron.workforce.operating.WorkerCognitionContextProjector;
import com.metatron.workforce.runtime.ObjectiveWorkspaceService;
import com.metatron.workforce.runtime.RepositoryWorkspaceMaterializationState;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** General code/file/process/Git/build/test execution composed from governed Action Fabric actions. */
@Component
public final class GeneralWorkspaceAutonomousCapability implements AutonomousExecutionCapability {
    public static final String CAPABILITY = "execution.general.workspace";
    public static final String WORKER_ID = "WORKER-GENERAL-ENGINEERING";
    public static final String AUTHORITY_REFERENCE = "policy:founder-general-engineering-workspace:v1";
    public static final String AUTHORIZATION_REFERENCE = "authorization:founder-general-engineering-workspace:v1";
    /**
     * The single GitHub owner every Founder-authorized repository -- canonical or newly created --
     * lives under (kelvinka38/universal, kelvinka38/metatron-institution, kelvinka38/metatron-workforce,
     * kelvinka38/bios, and any future repository). None of the canonical SoT documents
     * (WORKFORCE_SOT.md, 14_EXECUTION/SOT.md, SOT_ENFORCEMENT_DETAILED_GAP_CLOSURE.md,
     * WORKFORCE_AUTHORIZATION_EXECUTION_ATTRIBUTION_ARCHITECTURE.md) establish a policy of routing a
     * brand-new, unrelated app Objective at an existing canonical repository merely because that
     * repository already resolves authority -- so this owner is used only to derive a governed target
     * for a brand-new app's own (not-yet-existing) repository, never to silently point unrelated new
     * work at kelvinka38/metatron-workforce itself.
     */
    public static final String FOUNDER_GITHUB_OWNER = "kelvinka38";
    private static final int MAX_COGNITIVE_CYCLES = 48;
    private static final int MAX_RESEARCH_QUERY_CHARS = 20_000;
    static final String MEMORY_WORKSPACE_MATERIALIZED = "workspaceMaterialized";

    // Explicit read-only inspection intent (inspect/review/analyze/explain) takes precedence over an
    // incidental mutating-looking noun in the same objective (e.g. "analyze the build configuration" is
    // read-only even though it contains "build"); anything else naming an implementation/effecting
    // action (build/create/implement/write/modify/fix/patch/commit/publish/push/PR/deploy) defaults to
    // MUTATING, the pre-existing, already-governed assumption for real workspace execution.
    private static final java.util.regex.Pattern READ_ONLY_INTENT = java.util.regex.Pattern.compile(
            "(?i)\\b(inspect\\w*|review\\w*|analy[sz]\\w*|explain\\w*)\\b");

    private final GeneralWorkspaceActionCatalog actions;
    private final GeneralCognitiveWorkerBrainFactory brains;
    private final WorkerRuntimeProfileBindingService profiles;
    private final ObjectiveWorkspaceService workspaces;
    private final WorkerConstitutionRuntimeMaterializer runtimeConstitution;
    private final ExecutionGate executionGate;

    @Autowired
    public GeneralWorkspaceAutonomousCapability(GeneralWorkspaceActionCatalog actions,
                                                GeneralCognitiveWorkerBrainFactory brains,
                                                WorkerRuntimeProfileBindingService profiles,
                                                ObjectiveWorkspaceService workspaces,
                                                WorkerConstitutionRuntimeMaterializer runtimeConstitution,
                                                ExecutionGate executionGate) {
        this.actions = Objects.requireNonNull(actions, "actions");
        this.brains = Objects.requireNonNull(brains, "brains");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.runtimeConstitution = Objects.requireNonNull(runtimeConstitution, "runtimeConstitution");
        this.executionGate = Objects.requireNonNull(executionGate, "executionGate");
    }

    public GeneralWorkspaceAutonomousCapability(GeneralWorkspaceActionCatalog actions,
                                                GeneralCognitiveWorkerBrainFactory brains,
                                                WorkerRuntimeProfileBindingService profiles,
                                                ObjectiveWorkspaceService workspaces) {
        this.actions = Objects.requireNonNull(actions, "actions");
        this.brains = Objects.requireNonNull(brains, "brains");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.runtimeConstitution = null;
        this.executionGate = null;
    }

    @Override public String capabilityRef() { return CAPABILITY; }
    @Override public String authorityReference() { return AUTHORITY_REFERENCE; }
    @Override public String authorizationReference() { return AUTHORIZATION_REFERENCE; }
    @Override public double minimumCapabilityLevel() { return 1.0; }
    @Override public double requiredCapacity() { return 1.0; }
    @Override public boolean supportsWorker(String workerId) { return WORKER_ID.equals(workerId); }

    @Override
    public String capabilityDescription() {
        return CAPABILITY + " — general Cognitive Worker using governed Objective workspace and isolated sandbox actions";
    }

    @Override
    public CapabilityResult execute(CapabilityRequest request) {
        Objects.requireNonNull(request, "request");
        if (!request.allocated()) throw new SecurityException("governed allocation required for general workspace execution");
        if (!WORKER_ID.equals(request.allocatedWorkerId())) throw new SecurityException("general workspace worker mismatch");
        if (!AUTHORIZATION_REFERENCE.equals(request.authorizationReference())) {
            throw new SecurityException("general workspace authorization mismatch");
        }
        if (request.workSpec().consequence() == ExecutionWorkSpec.Consequence.MUTATING
                && (!request.governanceBound() || executionGate == null)) {
            throw new SecurityException("sot-governance-binding-required-for-mutating-general-workspace");
        }
        WorkerRuntimeProfileBindingService.Binding binding = profiles.requireBinding(request.allocatedWorkerId());
        if (!WorkerRuntimeProfileBindingService.GENERAL_ENGINEERING_PROFILE.equals(binding.profile().profileRef())) {
            throw new SecurityException("general workspace runtime profile mismatch");
        }
        if (!binding.profile().writableWorkspace()) {
            throw new SecurityException("general workspace profile is not writable");
        }

        ObjectiveWorkspaceService.ObjectiveWorkspace workspace = workspaces.provision(
                request.objectiveId(), request.allocatedWorkerId());
        Map<String, String> objectiveMemory = new LinkedHashMap<>(objectiveWorkspaceMemory(workspaces, workspace));
        WorkerConstitutionRuntimeMaterializer.RuntimeConstitution constitutionSnapshot = null;
        if (runtimeConstitution != null) {
            constitutionSnapshot = runtimeConstitution.materializeForAssignment(
                    request.allocatedWorkerId(), request.assignmentReference(), Instant.now());
            objectiveMemory.put("workerConstitutionSnapshotRef", constitutionSnapshot.snapshotId());
            objectiveMemory.put("workerConstitutionMaterializedAt", constitutionSnapshot.materializedAt().toString());
            objectiveMemory.put("workerConstitution", WorkerCognitionContextProjector.forAssignment(
                    constitutionSnapshot, request.assignmentReference()));
        }
        List<ActionFabric.Action> governedActions = actionsForWork(
                actions.actions(request.allocatedWorkerId(), request.authorizationReference(), request.objectiveId()),
                request.workSpec(), objectiveMemory);
        ActionFabric fabric = new ActionFabric(governedActions, executionGate);

        GeneralCognitiveWorkerBrain frontierBrain = null;
        CognitiveWorkerRuntime.Brain selectedBrain;
        List<String> brainEvidence = new ArrayList<>();
        if (requiresExternalResearch(request.workSpec())) {
            selectedBrain = deterministicExternalResearchBrain(request.workSpec());
            brainEvidence.add("deterministic-governed-research-brain:v1");
            brainEvidence.add("deterministic-governed-research-action:" + GeneralWebResearchAction.ACTION_REF);
        } else {
            frontierBrain = brains.create();
            selectedBrain = frontierBrain;
        }
        CognitiveWorkerRuntime.Brain contextualBrain = withObjectiveWorkspaceMemory(selectedBrain, objectiveMemory);
        ActionJournal actionJournal = ActionJournal.runtimeEvidenceJournal();
        CognitiveWorkerRuntime runtime = new CognitiveWorkerRuntime(
                fabric, actionJournal, MAX_COGNITIVE_CYCLES, executionGate);
        CognitiveWorkerRuntime.Outcome outcome = runtime.execute(
                request.allocatedWorkerId(),
                request.assignmentReference(),
                request.authorizationReference(),
                request.objectiveId(),
                request.workSpec(),
                request.idempotencyKey(),
                request.governanceContext(),
                contextualBrain);

        LinkedHashSet<String> durableEvidence = new LinkedHashSet<>(
                actionJournal.objectiveEvidenceReferences(request.objectiveId()));
        durableEvidence.addAll(outcome.evidenceReferences());
        List<String> evidence = new ArrayList<>(durableEvidence);
        if (outcome.success()) evidence.add("general-work-output:" + outcome.summary());
        if (frontierBrain != null) evidence.addAll(frontierBrain.evidenceReferences());
        evidence.addAll(brainEvidence);
        evidence.add("general-action-composition:capability=" + request.workSpec().requiredCapability()
                + ":workspace=" + workspace.workspaceRef()
                + ":profile=" + binding.profile().profileRef());
        if (request.governanceContext() != null) {
            evidence.add("governance-plan:" + request.governanceContext().planId() + "@" + request.governanceContext().planVersion());
            evidence.add("governance-authority-snapshot:" + request.governanceContext().authoritySnapshotId());
            evidence.add("governance-attempt:" + request.governanceContext().attemptId()
                    + ":fence=" + request.governanceContext().fencingToken());
        }
        if (constitutionSnapshot != null) {
            evidence.add("worker-constitution-runtime-snapshot:" + constitutionSnapshot.snapshotId());
            constitutionSnapshot.evidenceReferences().stream()
                    .filter(ref -> ref != null && !ref.isBlank()).limit(300).forEach(evidence::add);
        }
        evidence.add("general-action-catalog:" + governedActions.stream().map(ActionFabric.Action::actionRef).sorted().toList());
        evidence.add("general-workspace-continuity:materialized="
                + objectiveMemory.getOrDefault(MEMORY_WORKSPACE_MATERIALIZED, "false")
                + ":repository=" + objectiveMemory.getOrDefault("repository", "none")
                + ":source=" + objectiveMemory.getOrDefault("sourceCommitSha", "none"));
        return new CapabilityResult(outcome.success(), request.allocatedWorkerId(), request.assignmentReference(),
                workspace.workspaceRef(), evidence, outcome.summary());
    }

    static Map<String, String> objectiveWorkspaceMemory(ObjectiveWorkspaceService workspaces,
                                                        ObjectiveWorkspaceService.ObjectiveWorkspace workspace) {
        Objects.requireNonNull(workspaces, "workspaces"); Objects.requireNonNull(workspace, "workspace");
        Map<String, String> memory = new LinkedHashMap<>();
        memory.put("workspaceRef", workspace.workspaceRef()); memory.put("workspaceKey", workspace.workspaceKey());
        memory.put(MEMORY_WORKSPACE_MATERIALIZED, "false");
        Path gitDirectory = workspaces.resolve(workspace, ".git");
        memory.put("workspaceGitInitialized",
                Boolean.toString(Files.isDirectory(gitDirectory, LinkOption.NOFOLLOW_LINKS)));
        Path provenance = workspaces.resolve(workspace, ".metatron-repository");
        if (!Files.exists(provenance, LinkOption.NOFOLLOW_LINKS)) return Map.copyOf(memory);
        if (!Files.isRegularFile(provenance, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("objective workspace repository provenance is not a regular file");
        String baselineSha = RepositoryWorkspaceMaterializationState.completedBaselineSha(workspaces, workspace);
        if (baselineSha.isBlank()) return Map.copyOf(memory);
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : workspaces.read(workspace, ".metatron-repository").lines().toList()) {
            int split = line.indexOf('='); if (split > 0) fields.put(line.substring(0, split).trim(), line.substring(split + 1).trim());
        }
        String repository = fields.getOrDefault("repository", "");
        String requestedRef = fields.getOrDefault("requestedRef", "");
        String sourceCommitSha = fields.getOrDefault("commitSha", "");
        if (repository.isBlank() || !repository.contains("/") || requestedRef.isBlank()
                || !sourceCommitSha.matches("[0-9a-fA-F]{40}")) throw new IllegalStateException("objective workspace repository provenance is invalid");
        memory.put(MEMORY_WORKSPACE_MATERIALIZED, "true"); memory.put("repository", repository);
        memory.put("requestedRef", requestedRef); memory.put("sourceCommitSha", sourceCommitSha.toLowerCase(Locale.ROOT));
        memory.put("localBaselineCommitSha", baselineSha);
        return Map.copyOf(memory);
    }

    static List<ActionFabric.Action> actionsForWork(List<ActionFabric.Action> candidates,
                                                    ExecutionWorkSpec workSpec,
                                                    Map<String, String> objectiveMemory) {
        Objects.requireNonNull(candidates, "candidates"); Objects.requireNonNull(workSpec, "workSpec");
        Map<String, String> memory = objectiveMemory == null ? Map.of() : Map.copyOf(objectiveMemory);
        if (requiresExternalResearch(workSpec)) {
            return candidates.stream().filter(action -> GeneralWebResearchAction.ACTION_REF.equals(action.actionRef())).toList();
        }
        List<ActionFabric.Action> phaseScoped = phaseScopedActions(candidates, workSpec);
        boolean alreadyMaterialized = "true".equalsIgnoreCase(memory.getOrDefault(MEMORY_WORKSPACE_MATERIALIZED, "false"));
        boolean freshNewApplication = workSpec.evidenceRequirements().stream()
                .anyMatch("workspace-source:fresh-new-application"::equalsIgnoreCase);
        if (freshNewApplication && !requiresRepositoryMaterialization(workSpec)) {
            return phaseScoped.stream()
                    .filter(action -> !"workspace.repository.materialize".equals(action.actionRef()))
                    .toList();
        }
        if (!alreadyMaterialized || requiresRepositoryMaterialization(workSpec)) return List.copyOf(phaseScoped);
        return phaseScoped.stream()
                .filter(action -> !"workspace.repository.materialize".equals(action.actionRef()))
                .toList();
    }

    private static List<ActionFabric.Action> phaseScopedActions(
            List<ActionFabric.Action> candidates,
            ExecutionWorkSpec workSpec) {
        boolean produce = workSpec.evidenceRequirements().contains(GeneralWorkspacePhasePlanner.PHASE_PRODUCE);
        boolean verify = workSpec.evidenceRequirements().contains(GeneralWorkspacePhasePlanner.PHASE_VERIFY);
        boolean deliver = workSpec.evidenceRequirements().contains(GeneralWorkspacePhasePlanner.PHASE_DELIVER);
        if (!produce && !verify && !deliver) return List.copyOf(candidates);

        return candidates.stream().filter(action -> {
            String ref = action.actionRef();
            if (produce) {
                return ref.equals("workspace.repository.materialize")
                        || ref.equals("workspace.file.read")
                        || ref.equals("workspace.file.list")
                        || ref.equals("workspace.file.search")
                        || ref.equals("workspace.file.patch")
                        || ref.equals("workspace.file.write")
                        || ref.equals("workspace.process.run")
                        || ref.equals("workspace.shell.run");
            }
            if (verify) {
                return ref.equals("workspace.file.read")
                        || ref.equals("workspace.file.list")
                        || ref.equals("workspace.file.search")
                        || ref.equals("workspace.dependencies.install")
                        || ref.equals("workspace.build.run")
                        || ref.equals("workspace.test.run")
                        || ref.equals("workspace.process.run");
            }
            return ref.equals("workspace.file.read")
                    || ref.equals("workspace.file.list")
                    || ref.equals("workspace.file.search")
                    || ref.equals("workspace.git.status")
                    || ref.equals("workspace.git.diff")
                    || ref.equals("workspace.git.run")
                    || ref.equals("workspace.github.pr.publish");
        }).toList();
    }

    static boolean requiresExternalResearch(ExecutionWorkSpec workSpec) {
        String semantic = (workSpec.objective() + " " + workSpec.target() + " "
                + workSpec.acceptanceCriteria() + " " + workSpec.evidenceRequirements()).toLowerCase(Locale.ROOT);
        boolean explicit = workSpec.evidenceRequirements().stream().map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(value -> value.contains("research-action:research.web.search")
                        || value.contains("requested-capability:") && value.contains("research"));
        if (explicit) return true;
        boolean researchIntent = semantic.contains("research") || semantic.contains("paper")
                || semantic.contains("publication") || semantic.contains("report")
                || semantic.contains("standard") || semantic.contains("regulator") || semantic.contains("regulatory");
        boolean externalEvidence = semantic.contains("source") || semantic.contains("evidence")
                || semantic.contains("external") || semantic.contains("web") || semantic.contains("internet")
                || semantic.contains("recent") || semantic.contains("current") || semantic.contains("new ");
        return researchIntent && externalEvidence;
    }

    static CognitiveWorkerRuntime.Brain deterministicExternalResearchBrain(ExecutionWorkSpec workSpec) {
        Objects.requireNonNull(workSpec, "workSpec");
        if (!requiresExternalResearch(workSpec)) {
            throw new IllegalArgumentException("deterministic research brain requires explicit external research work");
        }
        String query = deterministicResearchQuery(workSpec);
        return new CognitiveWorkerRuntime.Brain() {
            @Override
            public CognitiveWorkerRuntime.Thought think(CognitiveWorkerRuntime.CognitiveContext context) {
                return new CognitiveWorkerRuntime.Thought(
                        GeneralWebResearchAction.ACTION_REF,
                        Map.of("query", query),
                        "execute the explicitly governed read-only public research action without frontier cognition");
            }

            @Override
            public CognitiveWorkerRuntime.Reflection reflect(CognitiveWorkerRuntime.CognitiveContext context,
                                                               ActionFabric.ActionObservation observation) {
                if (!GeneralWebResearchAction.ACTION_REF.equals(observation.actionRef())) {
                    return CognitiveWorkerRuntime.Reflection.failed("unexpected research action observation");
                }
                if (!observation.success()) {
                    return CognitiveWorkerRuntime.Reflection.failed(
                            "governed public research failed: " + observation.summary());
                }
                return CognitiveWorkerRuntime.Reflection.complete(
                        "governed public research completed with attributable Action evidence");
            }
        };
    }

    static String deterministicResearchQuery(ExecutionWorkSpec workSpec) {
        StringBuilder query = new StringBuilder();
        query.append(workSpec.objective().trim());
        if (!workSpec.target().isBlank()) query.append("\nTarget: ").append(workSpec.target().trim());
        if (!workSpec.acceptanceCriteria().isEmpty()) {
            query.append("\nAcceptance criteria: ").append(String.join("; ", workSpec.acceptanceCriteria()));
        }
        if (!workSpec.evidenceRequirements().isEmpty()) {
            query.append("\nEvidence requirements: ").append(String.join("; ", workSpec.evidenceRequirements()));
        }
        String value = query.toString().trim();
        if (value.length() > MAX_RESEARCH_QUERY_CHARS) value = value.substring(0, MAX_RESEARCH_QUERY_CHARS);
        if (value.isBlank()) throw new IllegalArgumentException("research query must not be blank");
        return value;
    }

    static boolean requiresRepositoryMaterialization(ExecutionWorkSpec workSpec) {
        String objective = workSpec.objective().toLowerCase(Locale.ROOT);
        return objective.contains("materializ") || objective.contains("snapshot") || objective.contains("checkout");
    }

    /**
     * Deterministic Consequence classification for an explicit General Workspace objective. A genuinely
     * read-only inspection request (inspect/review/analyze/explain) stays READ_ONLY even if it mentions
     * a mutating-sounding noun in passing (e.g. "analyze the build configuration"). Anything naming an
     * implementation/effecting action (build/create/implement/write/modify/fix/patch/commit/publish/
     * push/PR/deploy/code-generation) is MUTATING. Unclassified text defaults to MUTATING, the safer,
     * already-governed assumption for real workspace execution.
     */
    public static ExecutionWorkSpec.Consequence classifyConsequence(String objective) {
        String semantic = objective == null ? "" : objective.toLowerCase(Locale.ROOT);
        if (READ_ONLY_INTENT.matcher(semantic).find()) return ExecutionWorkSpec.Consequence.READ_ONLY;
        return ExecutionWorkSpec.Consequence.MUTATING;
    }

    static CognitiveWorkerRuntime.Brain withObjectiveWorkspaceMemory(CognitiveWorkerRuntime.Brain delegate,
                                                                     Map<String, String> objectiveMemory) {
        Objects.requireNonNull(delegate, "delegate");
        Map<String, String> persistent = objectiveMemory == null ? Map.of() : Map.copyOf(objectiveMemory);
        return new CognitiveWorkerRuntime.Brain() {
            @Override public CognitiveWorkerRuntime.Thought think(CognitiveWorkerRuntime.CognitiveContext context) {
                return delegate.think(withMemory(context, persistent));
            }
            @Override public CognitiveWorkerRuntime.Reflection reflect(CognitiveWorkerRuntime.CognitiveContext context,
                                                                        ActionFabric.ActionObservation observation) {
                return delegate.reflect(withMemory(context, persistent), observation);
            }
            @Override public boolean blocksCompletionForUnresolvedFailure(CognitiveWorkerRuntime.CognitiveContext context,
                                                                           String actionRef) {
                return delegate.blocksCompletionForUnresolvedFailure(withMemory(context, persistent), actionRef);
            }
        };
    }

    private static CognitiveWorkerRuntime.CognitiveContext withMemory(CognitiveWorkerRuntime.CognitiveContext context,
                                                                      Map<String, String> persistent) {
        Map<String, String> merged = new LinkedHashMap<>(persistent); merged.putAll(context.memory());
        return new CognitiveWorkerRuntime.CognitiveContext(context.workerId(), context.assignmentReference(),
                context.authorizationReference(), context.objectiveId(), context.workSpec(), context.idempotencyKey(),
                context.availableActions(), context.history(), Map.copyOf(merged));
    }
}