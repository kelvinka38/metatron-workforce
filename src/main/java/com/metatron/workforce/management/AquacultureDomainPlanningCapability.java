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
    /** Upper bound per governance-input digest (CTO decision on the 16,000-char context budget). */
    static final int MAX_DIGEST_CHARS = 1_500;
    /**
     * Characters all governance digests may occupy together in one planning request; with more inputs each
     * digest gets proportionally less, never more than {@link #MAX_DIGEST_CHARS}.
     */
    static final int DIGEST_POOL_CHARS = 7_200;
    /** Bound on a raw search/read result the planning brain may see (workspace.file.search, ad hoc reads). */
    static final int MAX_LOOKUP_RESULT_CHARS = 2_000;
    /** Every request to cognition (instructions + context), as enforced by GeneralCognitiveWorkerBrain. */
    static final int MAX_REQUEST_CHARS = 16_000;
    private static final int MAX_HISTORY_SUMMARY_CHARS = 1_500;
    private static final String MEMORY_DIGEST_PREFIX = "governanceDigest:";
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
        ReadDigestPlanBrain hoaBrain = new ReadDigestPlanBrain(brain, workspace);
        CognitiveWorkerRuntime.Brain governed =
                GeneralWorkspaceAutonomousCapability.withObjectiveWorkspaceMemory(hoaBrain, memory);
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
            String digest = hoaBrain.digests().get(path);
            if (digest != null) evidence.add("hoa-digest:" + path + ":" + digest.length());
            else if (read.contains(path)) problems.add("hoa-digest-missing:" + path);
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

    /**
     * Read → digest → plan (CTO decision: the 16,000-char cognition budget stays; raw governance text never reaches
     * the planning brain).
     * <ol>
     *   <li>READ: each required input is read with its own governed workspace.file.read (hoa-input-read evidence).</li>
     *   <li>DIGEST: right after each read, one separate bounded cognition call (map-reduce for inputs larger than
     *       one request) produces a digest of at most {@link #digestLimit} chars, kept in Objective memory under
     *       {@code governanceDigest:<path>}; history keeps only "read ok: &lt;path&gt; (&lt;bytes&gt; bytes)".</li>
     *   <li>PLAN: the brain sees the digests and current memory and may use workspace.file.search (results bounded to
     *       {@link #MAX_LOOKUP_RESULT_CHARS}); before every request the context is checked against
     *       {@link #MAX_REQUEST_CHARS} and older history is folded into one summary when needed.</li>
     * </ol>
     */
    private final class ReadDigestPlanBrain implements CognitiveWorkerRuntime.Brain {
        private final GeneralCognitiveWorkerBrain brain;
        private final ObjectiveWorkspaceService.ObjectiveWorkspace workspace;
        private final Map<String, String> digests = new LinkedHashMap<>();

        private ReadDigestPlanBrain(GeneralCognitiveWorkerBrain brain, ObjectiveWorkspaceService.ObjectiveWorkspace workspace) {
            this.brain = brain;
            this.workspace = workspace;
        }

        Map<String, String> digests() { return Map.copyOf(digests); }

        @Override
        public CognitiveWorkerRuntime.Thought think(CognitiveWorkerRuntime.CognitiveContext context) {
            if (!materialized(context)) return brain.think(planningView(context, List.of()));
            List<String> required = requiredInputs(workspace);
            for (String path : required) {
                if (!readAttempted(context.history(), path)) {
                    return new CognitiveWorkerRuntime.Thought("workspace.file.read", Map.of("path", path),
                            "Governed HOA precondition: read each required governance input individually before planning");
                }
            }
            return brain.think(withinBudget(planningView(context, required), null));
        }

        @Override
        public CognitiveWorkerRuntime.Reflection reflect(CognitiveWorkerRuntime.CognitiveContext context,
                                                         ActionFabric.ActionObservation observation) {
            String path = requiredReadPath(observation);
            if (path != null) {
                String content = observation.outputs().getOrDefault("content", "");
                if (!digests.containsKey(path)) {
                    List<String> required = requiredInputs(workspace);
                    digests.put(path, brain.digestDocument(planningView(context, required), path, content,
                            digestLimit(required.size())));
                }
                return CognitiveWorkerRuntime.Reflection.continueWith(readOk(path, content)
                        + "; digest " + digests.get(path).length() + " chars in memory " + MEMORY_DIGEST_PREFIX + path);
            }
            ActionFabric.ActionObservation bounded = boundedObservation(observation);
            return brain.reflect(withinBudget(planningView(context, requiredInputs(workspace)), bounded), bounded);
        }

        @Override
        public boolean blocksCompletionForUnresolvedFailure(CognitiveWorkerRuntime.CognitiveContext context, String actionRef) {
            return brain.blocksCompletionForUnresolvedFailure(planningView(context, List.of()), actionRef);
        }

        private String requiredReadPath(ActionFabric.ActionObservation observation) {
            if (!"workspace.file.read".equals(observation.actionRef()) || !observation.success()) return null;
            String path = observation.outputs().get("path");
            return path != null && requiredInputs(workspace).contains(path) ? path : null;
        }

        /**
         * What the brain may see: no raw governance text, digests keyed by path, bounded lookup results, and read
         * cycles reduced to "read ok: &lt;path&gt; (&lt;bytes&gt; bytes)".
         */
        private CognitiveWorkerRuntime.CognitiveContext planningView(CognitiveWorkerRuntime.CognitiveContext context,
                                                                     List<String> required) {
            Map<String, String> memory = new LinkedHashMap<>();
            context.memory().forEach((key, value) -> {
                if (!"content".equals(key)) memory.put(key, bounded(value, MAX_LOOKUP_RESULT_CHARS));
            });
            if (!required.isEmpty()) memory.put("requiredGovernanceInputs", String.join(";", required));
            digests.forEach((path, digest) -> memory.put(MEMORY_DIGEST_PREFIX + path, digest));
            List<CognitiveWorkerRuntime.Cycle> history = context.history().stream().map(this::viewCycle).toList();
            return new CognitiveWorkerRuntime.CognitiveContext(context.workerId(), context.assignmentReference(),
                    context.authorizationReference(), context.objectiveId(), context.workSpec(), context.idempotencyKey(),
                    context.availableActions(), history, Map.copyOf(memory));
        }

        private CognitiveWorkerRuntime.Cycle viewCycle(CognitiveWorkerRuntime.Cycle cycle) {
            ActionFabric.ActionObservation observation = cycle.observation();
            if ("workspace.file.read".equals(cycle.thought().actionRef()) && observation.success()) {
                String path = observation.outputs().getOrDefault("path", cycle.thought().inputs().getOrDefault("path", ""));
                String content = observation.outputs().getOrDefault("content", "");
                observation = new ActionFabric.ActionObservation(observation.actionRef(), true, readOk(path, content),
                        Map.of("path", path, "bytes", Integer.toString(utf8Bytes(content))),
                        observation.evidenceReferences(), observation.observedAt());
            } else {
                observation = boundedObservation(observation);
            }
            return new CognitiveWorkerRuntime.Cycle(cycle.number(), cycle.thought(), observation, cycle.reflection());
        }

        /**
         * Keeps the request within {@link #MAX_REQUEST_CHARS}: older cycles are folded into one deterministic history
         * summary (kept in memory) until the request fits; a request that still cannot fit fails explicitly.
         */
        private CognitiveWorkerRuntime.CognitiveContext withinBudget(CognitiveWorkerRuntime.CognitiveContext view,
                                                                     ActionFabric.ActionObservation observation) {
            CognitiveWorkerRuntime.CognitiveContext candidate = view;
            for (int keep : new int[] {view.history().size(), 4, 2, 1, 0}) {
                if (keep > view.history().size()) continue;
                candidate = keepingLatest(view, keep);
                if (requestChars(candidate, observation) <= MAX_REQUEST_CHARS) return candidate;
            }
            if (observation != null) {
                // Reflecting on a non-read action does not need the governance digests; they stay in Objective
                // memory and return with the next planning request.
                candidate = withoutDigests(candidate);
                if (requestChars(candidate, observation) <= MAX_REQUEST_CHARS) return candidate;
            }
            String largest = candidate.memory().entrySet().stream()
                    .sorted((left, right) -> Integer.compare(right.getValue().length(), left.getValue().length()))
                    .limit(5).map(entry -> entry.getKey() + "=" + entry.getValue().length())
                    .collect(java.util.stream.Collectors.joining(","));
            throw new IllegalStateException("hoa-planning-request-over-budget:chars="
                    + requestChars(candidate, observation) + ":limit=" + MAX_REQUEST_CHARS + ":largest-memory=" + largest);
        }

        private CognitiveWorkerRuntime.CognitiveContext withoutDigests(CognitiveWorkerRuntime.CognitiveContext view) {
            Map<String, String> memory = new LinkedHashMap<>();
            view.memory().forEach((key, value) -> {
                if (!key.startsWith(MEMORY_DIGEST_PREFIX)) memory.put(key, value);
            });
            memory.put("governanceDigestsOmitted", "kept in Objective memory for planning: " + String.join(";", digests.keySet()));
            return new CognitiveWorkerRuntime.CognitiveContext(view.workerId(), view.assignmentReference(),
                    view.authorizationReference(), view.objectiveId(), view.workSpec(), view.idempotencyKey(),
                    view.availableActions(), view.history(), Map.copyOf(memory));
        }

        private int requestChars(CognitiveWorkerRuntime.CognitiveContext context, ActionFabric.ActionObservation observation) {
            return observation == null ? brain.actionSelectionRequestChars(context)
                    : brain.reflectionRequestChars(context, observation);
        }

        /**
         * Folds cycles older than the latest {@code keep} into one history summary and reduces them to their
         * identity (action, inputs bounded to 200 chars, success, short summary). No cycle is removed: the brain's
         * deterministic preconditions (git add/commit, PR publish) scan the whole history.
         */
        private CognitiveWorkerRuntime.CognitiveContext keepingLatest(CognitiveWorkerRuntime.CognitiveContext view, int keep) {
            List<CognitiveWorkerRuntime.Cycle> history = view.history();
            if (keep >= history.size()) return view;
            int folded = history.size() - keep;
            StringBuilder summary = new StringBuilder();
            List<CognitiveWorkerRuntime.Cycle> reduced = new ArrayList<>();
            for (int i = 0; i < history.size(); i++) {
                CognitiveWorkerRuntime.Cycle cycle = history.get(i);
                if (i >= folded) {
                    reduced.add(cycle);
                    continue;
                }
                String target = cycle.thought().inputs().getOrDefault("path", "");
                summary.append('c').append(cycle.number()).append(' ').append(cycle.thought().actionRef())
                        .append(cycle.observation().success() ? " ok" : " FAILED")
                        .append(target.isBlank() ? "" : " " + target).append("; ");
                Map<String, String> inputs = new LinkedHashMap<>();
                cycle.thought().inputs().forEach((key, value) -> inputs.put(key, bounded(value, 200)));
                ActionFabric.ActionObservation observation = cycle.observation();
                Map<String, String> outputs = observation.outputs().containsKey("path")
                        ? Map.of("path", observation.outputs().get("path")) : Map.of();
                reduced.add(new CognitiveWorkerRuntime.Cycle(cycle.number(),
                        new CognitiveWorkerRuntime.Thought(cycle.thought().actionRef(), inputs,
                                bounded(cycle.thought().rationale(), 200)),
                        new ActionFabric.ActionObservation(observation.actionRef(), observation.success(),
                                bounded(observation.summary(), 160), outputs, observation.evidenceReferences(),
                                observation.observedAt()),
                        new CognitiveWorkerRuntime.Reflection(cycle.reflection().decision(),
                                bounded(cycle.reflection().summary(), 160))));
            }
            Map<String, String> memory = new LinkedHashMap<>(view.memory());
            memory.put("historySummary", bounded(summary.toString().strip(), MAX_HISTORY_SUMMARY_CHARS));
            return new CognitiveWorkerRuntime.CognitiveContext(view.workerId(), view.assignmentReference(),
                    view.authorizationReference(), view.objectiveId(), view.workSpec(), view.idempotencyKey(),
                    view.availableActions(), List.copyOf(reduced), Map.copyOf(memory));
        }
    }

    /** Per-input digest limit: at most {@link #MAX_DIGEST_CHARS}, and all inputs together within the pool. */
    static int digestLimit(int requiredInputs) {
        return Math.min(MAX_DIGEST_CHARS, DIGEST_POOL_CHARS / Math.max(1, requiredInputs));
    }

    private static ActionFabric.ActionObservation boundedObservation(ActionFabric.ActionObservation observation) {
        Map<String, String> outputs = new LinkedHashMap<>();
        observation.outputs().forEach((key, value) -> outputs.put(key, bounded(value, MAX_LOOKUP_RESULT_CHARS)));
        return new ActionFabric.ActionObservation(observation.actionRef(), observation.success(),
                bounded(observation.summary(), MAX_LOOKUP_RESULT_CHARS), outputs, observation.evidenceReferences(),
                observation.observedAt());
    }

    private static String bounded(String value, int maxChars) {
        String text = value == null ? "" : value;
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "...[bounded original_chars=" + text.length() + "]";
    }

    private static String readOk(String path, String content) {
        return "read ok: " + path + " (" + utf8Bytes(content) + " bytes)";
    }

    private static int utf8Bytes(String content) {
        return content == null ? 0 : content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
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
