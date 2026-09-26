package com.metatron.workforce.action;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.intelligence.CognitiveOutputBudget;
import com.metatron.workforce.interaction.intelligence.GeneralWorkspacePhasePlanner;
import com.metatron.workforce.interaction.intelligence.WorkerIntelligenceService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** General Cognitive Worker brain. Reasoning is requested through Intelligence; action authority remains in ActionFabric. */
public final class GeneralCognitiveWorkerBrain implements CognitiveWorkerRuntime.Brain {
    private static final Pattern OWNER_REPOSITORY = Pattern.compile("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$");
    private static final Pattern EXACT_GIT_SHA = Pattern.compile("(?<![0-9a-fA-F])[0-9a-fA-F]{40}(?![0-9a-fA-F])");
    private static final Pattern WORKSPACE_FILE_PATH = Pattern.compile(
            "(?<![A-Za-z0-9_.-])([A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)+\\.[A-Za-z0-9_.-]+)(?![A-Za-z0-9_.-])");
    private static final Pattern EXACT_TEXT_REPLACEMENT = Pattern.compile(
            "(?is)\\bread\\s+([^\\s]+)\\s+and\\s+replace\\s+exactly\\s+one\\s+.*?'([^'\\r\\n]+)'\\s+with\\s+'([^'\\r\\n]+)'");
    private static final Pattern RESEARCH_TOP_N = Pattern.compile("(?i)\\b(?:top\\s*|exactly\\s+)(\\d{1,2})\\b");
    private static final Pattern RESEARCH_URL = Pattern.compile("https?://[^\\s)\\]}>;,]+");
    private static final Pattern RESEARCH_ITEM = Pattern.compile("(?m)^\\s*(?:\\d+[.)]|[-*]\\s*\\d+[.)])\\s+");
    private static final Pattern RESEARCH_DECISION = Pattern.compile("(?i)\\b(?:KEEP|TEST|CHANGE|REJECT)\\b");
    private static final Pattern NODE_START_SCRIPT = Pattern.compile("\"start\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern NODE_START_ENTRY = Pattern.compile("^node\\s+([\\w./-]+\\.m?js)\\b");
    private static final Pattern NODE_MAIN_FIELD = Pattern.compile("\"main\"\\s*:\\s*\"([^\"]*\\.m?js)\"");
    private static final ObjectMapper ACTION_INPUT_JSON = new ObjectMapper();
    // Root-cause fix (production incident, 2026-09-22): a real VERIFY-phase cognitive request for
    // WORKER-GENERAL-ENGINEERING (its full available-action catalog, acceptance criteria, evidence
    // requirements and memory alone, with recentCycles already empty -- nothing left to compact) rendered
    // to 8,753 chars against this budget, so the existing fail-closed guard fired even though it had
    // nothing left to trim. The bounded-local-retry policy then repeated the identical, deterministically
    // oversized request until escalation, since retrying without any change to the prompt could never
    // shrink it below budget. This raises the ceiling with real headroom over the observed case rather
    // than removing the bound: the budget must stay finite and enforced, but 7,000 chars proved too small
    // for a realistic non-history VERIFY payload before any history is even added.
    static final int MAX_CONTEXT_PROMPT_CHARS = 16_000;
    private static final int MAX_HISTORY_OUTPUT_VALUE_CHARS = 1_000;
    private static final int MAX_HISTORY_SUMMARY_CHARS = 500;
    // Action-contract input keys whose value is generated source/work-product content rather than a
    // short reference (path, command, SHA): when the offered catalog includes any of these, the
    // cognitive response's JSON must itself carry that content and needs the larger output budget.
    private static final Set<String> CONTENT_BEARING_INPUT_KEYS = Set.of("content", "newText", "body");
    private static final String ACTION_SELECTION_SYSTEM = """
            You are the action-selection brain for a governed Metatron Cognitive Worker; you have no execution authority.
            workerConstitution is the assignment-scoped cognitive projection of the durable Worker Constitution. Historical
            relationships remain durable by snapshot reference. Role or runtime profile never self-grants authority.
            Choose exactly one action from availableActions, using only keys listed in actionInputKeys, grounded in Work,
            memory and recent observations. Inspect unfamiliar code before editing. After failure, inspect/change state before
            retrying; never blindly repeat an identical failed action. Materialize a required repository before Git/build/test
            or repository-file work. sourceCommitSha proves source identity; local baseline/HEAD are workspace identities.
            Do not claim completion. Inputs are strings; encode list arguments as JSON strings in argsJson/tasksJson.
            Return ONLY JSON: {"actionRef":"...","inputs":{"key":"value"},"rationale":"short operational reason"}.
            """;
    private static final String REFLECTION_SYSTEM = """
            You are the reflection brain for a governed Metatron Cognitive Worker.
            Decide from the actual Work, acceptance criteria, evidence requirements and observed action result.
            COMPLETE only when every acceptance requirement can be supported by actual observations already obtained.
            CONTINUE if another governed action can advance or verify the Work.
            FAILED only when the observed state makes bounded recovery impossible.
            A failed test/build/tool observation is normally recoverable: CONTINUE when search/read/patch/retry can still advance the Work.
            Do not repeat the identical failed action without an intervening state-changing or diagnostic action.
            Never treat a successful intermediate action as completion of unrelated acceptance criteria.
            Repository source identity is proven by workspace.repository.materialize output sourceCommitSha. localBaselineCommitSha and workspace.git.status headSha are local Objective-workspace identities and may intentionally differ from sourceCommitSha.
            For external research/synthesis Work, a COMPLETE summary is the durable Work output, not a status sentence. It MUST contain the requested substantive deliverable, preserve source URLs or canonical identifiers from observations, distinguish evidence from inference, and explicitly state uncertainty.
            Never claim that N items were found unless at least N distinct attributable source URLs were actually observed.
            When the Work asks for a Top-N shortlist, the COMPLETE summary MUST enumerate items 1..N. Each item must include title, issuer/authors, publication/update date when observed, Source: <observed URL>, What is new, Why it matters, and Decision: KEEP|TEST|CHANGE|REJECT. End with the highest-potential model experiment and remaining uncertainty.
            If there are not enough qualified sources yet, CONTINUE with a narrower governed search. Do not substitute generic encyclopedias, promotional pages, tourism pages, or unrelated sources for missing evidence.
            Return ONLY JSON: {"decision":"CONTINUE|COMPLETE|FAILED","summary":"evidence-based result or next-step reason"}.
            """;
    private final WorkerIntelligenceService intelligence;
    private final ObjectMapper json;
    private final List<String> evidence = new ArrayList<>();

    public GeneralCognitiveWorkerBrain(WorkerIntelligenceService intelligence, ObjectMapper json) {
        this.intelligence = Objects.requireNonNull(intelligence, "intelligence");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public CognitiveWorkerRuntime.Thought think(CognitiveWorkerRuntime.CognitiveContext context) {
        CognitiveWorkerRuntime.Thought researchPrecondition = researchSearchPrecondition(context);
        if (researchPrecondition != null) return researchPrecondition;
        CognitiveWorkerRuntime.Thought requiredPrecondition = repositoryMaterializationPrecondition(context);
        if (requiredPrecondition != null) return requiredPrecondition;
        CognitiveWorkerRuntime.Thought exactTextReplacement = governedExactTextReplacementPrecondition(context);
        if (exactTextReplacement != null) return exactTextReplacement;
        CognitiveWorkerRuntime.Thought requiredFileWrite = governedExactShaFileWritePrecondition(context);
        if (requiredFileWrite != null) return requiredFileWrite;
        CognitiveWorkerRuntime.Thought requiredProjectPrepare = governedProjectPreparePrecondition(context);
        if (requiredProjectPrepare != null) return requiredProjectPrepare;
        CognitiveWorkerRuntime.Thought requiredDependencies = governedDependencyPrecondition(context);
        if (requiredDependencies != null) return requiredDependencies;
        CognitiveWorkerRuntime.Thought requiredBuild = governedBuildPrecondition(context);
        if (requiredBuild != null) return requiredBuild;
        CognitiveWorkerRuntime.Thought requiredTest = governedTestPrecondition(context);
        if (requiredTest != null) return requiredTest;
        CognitiveWorkerRuntime.Thought requiredRuntime = governedRuntimePrecondition(context);
        if (requiredRuntime != null) return requiredRuntime;
        CognitiveWorkerRuntime.Thought requiredGit = governedGitPrecondition(context);
        if (requiredGit != null) return requiredGit;
        CognitiveWorkerRuntime.Thought remoteProposal = governedRemoteProposalPrecondition(context);
        if (remoteProposal != null) return remoteProposal;
        CognitiveWorkerRuntime.Thought gitInspection = readOnlyGitInspectionPrecondition(context);
        if (gitInspection != null) return gitInspection;

        String system = ACTION_SELECTION_SYSTEM;
        CognitiveWorkerRuntime.CognitiveContext providerContext = providerActionSelectionContext(context);
        CognitiveProviderResult providerResult = completeObject(
                providerContext, system, contextPrompt(providerContext), outputBudgetFor(providerContext));
        Map<String, Object> parsed = providerResult.parsed();
        String actionRef = text(parsed.get("actionRef"), "actionRef");
        String rationale = text(parsed.get("rationale"), "rationale");
        Map<String, String> inputs = ActionContractCatalog.normalizeProviderInputs(
                actionRef, stringMap(parsed.get("inputs")));
        return new CognitiveWorkerRuntime.Thought(actionRef, inputs, rationale);
    }

    static CognitiveWorkerRuntime.CognitiveContext providerActionSelectionContext(
            CognitiveWorkerRuntime.CognitiveContext context) {
        Objects.requireNonNull(context, "context");
        if (hasMarker(context, GeneralWorkspacePhasePlanner.PHASE_PREPARE)
                && hasMarker(context, GeneralWorkspacePhasePlanner.REQUIRE_MANIFEST)
                && !hasProjectManifestMutation(context)
                && context.availableActions().contains("workspace.project.prepare")) {
            return new CognitiveWorkerRuntime.CognitiveContext(
                    context.workerId(), context.assignmentReference(), context.authorizationReference(),
                    context.objectiveId(), context.workSpec(), context.idempotencyKey(),
                    List.of("workspace.project.prepare"), context.history(), context.memory());
        }
        if (!isFreshNewApplicationWork(context)) return context;

        List<String> providerActions;
        if (!(hasMarker(context, GeneralWorkspacePhasePlanner.PHASE_PRODUCE)
                        ? hasWorkspaceWorkProductMutation(context)
                        : hasWorkspaceSourceMutation(context))
                && context.availableActions().contains("workspace.file.write")) {
            // A planner-marked fresh application starts from an empty workspace. Force the first
            // provider-selected action to create actual source/work-product; Git/build/test/runtime
            // sequencing only becomes meaningful after at least one successful source mutation.
            providerActions = List.of("workspace.file.write");
        } else if (requiresProjectManifestBeforeLifecycle(context)
                && !hasProjectManifestMutation(context)) {
            // A fresh app that must build/test/run needs a project/dependency manifest before lifecycle
            // actions can be meaningful. Keep cognition in bounded scaffold creation/inspection until
            // it has created one instead of wasting cycles on dependency/build/test actions that can only fail.
            providerActions = context.availableActions().stream()
                    .filter(action -> action.startsWith("workspace.file."))
                    .toList();
        } else {
            // Git is a deterministic governed postcondition for fresh-app work. Let the existing
            // governedGitPrecondition()/remote-proposal preconditions perform add/commit/status only
            // after source/build/test/runtime prerequisites are satisfied; never spend model turns
            // inventing argsJson or staging an empty workspace.
            providerActions = context.availableActions().stream()
                    .filter(action -> !"workspace.git.run".equals(action))
                    .filter(action -> !"workspace.git.status".equals(action))
                    .filter(action -> !"workspace.github.pr.publish".equals(action))
                    .toList();
        }
        if (providerActions.equals(context.availableActions())) return context;
        return new CognitiveWorkerRuntime.CognitiveContext(
                context.workerId(),
                context.assignmentReference(),
                context.authorizationReference(),
                context.objectiveId(),
                context.workSpec(),
                context.idempotencyKey(),
                providerActions,
                context.history(),
                context.memory());
    }

    private static boolean requiresProjectManifestBeforeLifecycle(
            CognitiveWorkerRuntime.CognitiveContext context) {
        if (phased(context)) {
            return hasMarker(context, GeneralWorkspacePhasePlanner.PHASE_PREPARE)
                    && hasMarker(context, GeneralWorkspacePhasePlanner.REQUIRE_MANIFEST);
        }
        return requiresGovernedBuild(context)
                || requiresGovernedTest(context)
                || requiresRuntimeVerification(context)
                || context.availableActions().contains("workspace.dependencies.install");
    }

    private static boolean hasProjectManifestMutation(CognitiveWorkerRuntime.CognitiveContext context) {
        return context.history().stream().anyMatch(cycle -> {
            if (!cycle.observation().success()) return false;
            String action = cycle.thought().actionRef();
            if ("workspace.project.prepare".equals(action)) return true;
            if (!"workspace.file.write".equals(action) && !"workspace.file.patch".equals(action)) return false;
            return isProjectManifestPath(cycle.thought().inputs().getOrDefault("path", ""));
        });
    }

    private static boolean isProjectManifestPath(String rawPath) {
        String path = rawPath == null ? "" : rawPath.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
        return path.endsWith("/package.json") || "package.json".equals(path)
                || path.endsWith("/pyproject.toml") || "pyproject.toml".equals(path)
                || path.endsWith("/requirements.txt") || "requirements.txt".equals(path)
                || path.endsWith("/pom.xml") || "pom.xml".equals(path)
                || path.endsWith("/build.gradle") || "build.gradle".equals(path)
                || path.endsWith("/build.gradle.kts") || "build.gradle.kts".equals(path)
                || path.endsWith("/cargo.toml") || "cargo.toml".equals(path)
                || path.endsWith("/go.mod") || "go.mod".equals(path)
                || path.endsWith("/composer.json") || "composer.json".equals(path)
                || path.endsWith("/gemfile") || "gemfile".equals(path);
    }

    static CognitiveWorkerRuntime.Thought researchSearchPrecondition(
            CognitiveWorkerRuntime.CognitiveContext context) {
        Objects.requireNonNull(context, "context");
        if (!context.availableActions().contains(GeneralWebResearchAction.ACTION_REF)) return null;
        if (!requiresExternalResearch(context)) return null;
        if (successfulAction(context, GeneralWebResearchAction.ACTION_REF)) return null;

        StringBuilder query = new StringBuilder(context.workSpec().objective());
        if (!context.workSpec().target().isBlank()) {
            query.append("\nTarget: ").append(context.workSpec().target());
        }
        query.append("\nUse focused search terms. Prefer primary regulators, standards bodies, peer-reviewed papers,")
                .append(" or authoritative substantive research over generic encyclopedia, promotional, SEO, or travel content.");
        String bounded = query.toString();
        if (bounded.length() > 8_000) bounded = bounded.substring(0, 8_000);
        return new CognitiveWorkerRuntime.Thought(
                GeneralWebResearchAction.ACTION_REF,
                Map.of("query", bounded),
                "External research Work requires attributable current evidence before synthesis or completion");
    }

    static CognitiveWorkerRuntime.Thought repositoryMaterializationPrecondition(
            CognitiveWorkerRuntime.CognitiveContext context) {
        Objects.requireNonNull(context, "context");
        if (!context.availableActions().contains("workspace.repository.materialize")) return null;
        if (!requiresRepositoryMaterialization(context)) return null;
        boolean alreadyMaterialized = context.history().stream().anyMatch(cycle ->
                "workspace.repository.materialize".equals(cycle.thought().actionRef())
                        && cycle.observation().success());
        if (alreadyMaterialized) return null;

        String repository = repositoryFromTarget(context.workSpec().target());
        if (repository.isBlank()) return null;
        Map<String, String> inputs = new LinkedHashMap<>();
        inputs.put("repository", repository);
        String exactRef = exactRef(context);
        if (!exactRef.isBlank()) inputs.put("ref", exactRef);
        // Only ever set for a planner-derived brand-new destination (never an Objective-named existing
        // repository) -- see RepositoryWorkspaceMaterializationService.materialize(..., createIfMissing).
        if (isFreshNewApplicationWork(context)) inputs.put("createIfMissing", "true");
        Map<String, String> finalInputs = Map.copyOf(inputs);

        // Anti-livelock: a deterministic required-action precondition must not blindly force the exact
        // same action+inputs again after it has already failed once with nothing intervening to change
        // state. Forcing it every cycle regardless of outcome is what turned one real 404 into 48
        // identical cycles in production. Back off after the first identical failure and let cognition
        // (and, ultimately, the reflection-side anti-livelock backstop below) decide/terminate instead.
        boolean alreadyFailedIdentically = context.history().stream().anyMatch(cycle ->
                "workspace.repository.materialize".equals(cycle.thought().actionRef())
                        && !cycle.observation().success()
                        && cycle.thought().inputs().equals(finalInputs));
        if (alreadyFailedIdentically) return null;

        return new CognitiveWorkerRuntime.Thought(
                "workspace.repository.materialize",
                finalInputs,
                "Repository-backed Work requires a local immutable baseline before Git/build/test actions");
    }

    static CognitiveWorkerRuntime.Thought readOnlyGitInspectionPrecondition(
            CognitiveWorkerRuntime.CognitiveContext context) {
        Objects.requireNonNull(context, "context");
        if (!context.availableActions().contains("workspace.git.status")) return null;
        if (context.workSpec().consequence()
                != com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec.Consequence.READ_ONLY) return null;
        boolean alreadyInspected = context.history().stream().anyMatch(cycle ->
                "workspace.git.status".equals(cycle.thought().actionRef()) && cycle.observation().success());
        if (alreadyInspected) return null;
        boolean materialized = context.history().stream().anyMatch(cycle ->
                "workspace.repository.materialize".equals(cycle.thought().actionRef()) && cycle.observation().success());
        if (requiresRepositoryMaterialization(context) && !materialized) return null;
        String text = workText(context).toLowerCase(java.util.Locale.ROOT);
        boolean requiresIdentity = text.contains("git rev-parse")
                || text.contains("git log")
                || text.contains("head sha")
                || text.contains("head commit")
                || text.contains("commit identity")
                || text.contains("commit sha");
        if (!requiresIdentity) return null;
        return new CognitiveWorkerRuntime.Thought(
                "workspace.git.status", Map.of(),
                "READ_ONLY Work requires immutable local Git HEAD/history evidence; use governed read-only inspection");
    }

    static CognitiveWorkerRuntime.Thought governedExactTextReplacementPrecondition(
            CognitiveWorkerRuntime.CognitiveContext context) {
        Objects.requireNonNull(context, "context");
        if (context.workSpec().consequence()
                != com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec.Consequence.MUTATING) return null;
        if (!context.availableActions().contains("workspace.file.read")
                || !context.availableActions().contains("workspace.file.write")) return null;
        ExactTextReplacement replacement = exactTextReplacement(context);
        if (replacement == null || !materializationSatisfied(context)) return null;
        if (successfulFileAction(context, "workspace.file.write", replacement.path())) return null;

        CognitiveWorkerRuntime.Cycle read = latestSuccessfulFileRead(context, replacement.path());
        if (read == null) {
            return new CognitiveWorkerRuntime.Thought(
                    "workspace.file.read",
                    Map.of("path", replacement.path()),
                    "Read the exact governed source path before applying the requested bounded text replacement");
        }

        String content = read.observation().outputs().get("content");
        if (content == null) throw new IllegalStateException("workspace.file.read returned no content");
        int oldCount = countOccurrences(content, replacement.oldText());
        int newCount = countOccurrences(content, replacement.newText());
        if (oldCount == 0 && newCount == 1) return null;
        if (oldCount != 1) {
            throw new IllegalStateException(
                    "exact text replacement requires one old-text occurrence but observed " + oldCount);
        }
        String updated = content.replace(replacement.oldText(), replacement.newText());
        return new CognitiveWorkerRuntime.Thought(
                "workspace.file.write",
                Map.of("path", replacement.path(), "content", updated),
                "Apply exactly one requested source-text replacement through the governed workspace write action");
    }

    private static CognitiveWorkerRuntime.Cycle latestSuccessfulFileRead(
            CognitiveWorkerRuntime.CognitiveContext context,
            String path) {
        for (int i = context.history().size() - 1; i >= 0; i--) {
            CognitiveWorkerRuntime.Cycle cycle = context.history().get(i);
            if (!cycle.observation().success()) continue;
            if (!"workspace.file.read".equals(cycle.thought().actionRef())) continue;
            if (path.equals(cycle.thought().inputs().get("path"))) return cycle;
        }
        return null;
    }

    private static boolean successfulFileAction(
            CognitiveWorkerRuntime.CognitiveContext context,
            String actionRef,
            String path) {
        return context.history().stream().anyMatch(cycle ->
                cycle.observation().success()
                        && actionRef.equals(cycle.thought().actionRef())
                        && path.equals(cycle.thought().inputs().get("path")));
    }

    private static ExactTextReplacement exactTextReplacement(CognitiveWorkerRuntime.CognitiveContext context) {
        Matcher matcher = EXACT_TEXT_REPLACEMENT.matcher(workText(context));
        if (!matcher.find()) return null;
        String path = matcher.group(1).trim();
        if (!safeWorkspaceMutationPath(path)) {
            throw new SecurityException("unsafe exact replacement path: " + path);
        }
        String oldText = matcher.group(2);
        String newText = matcher.group(3);
        if (oldText.isEmpty() || newText.isEmpty() || oldText.equals(newText)) {
            throw new IllegalStateException("exact text replacement requires distinct non-empty text");
        }
        return new ExactTextReplacement(path, oldText, newText);
    }

    private static boolean safeWorkspaceMutationPath(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains("\\")
                || path.contains("..") || path.contains("\n") || path.contains("\r")
                || path.equals(".git") || path.startsWith(".git/")
                || path.equals(".metatron-workspace") || path.equals(".metatron-repository")) return false;
        return !OWNER_REPOSITORY.matcher(path).matches();
    }

    private static int countOccurrences(String content, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = content.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    static CognitiveWorkerRuntime.Thought governedExactShaFileWritePrecondition(
            CognitiveWorkerRuntime.CognitiveContext context) {
        Objects.requireNonNull(context, "context");
        if (!context.availableActions().contains("workspace.file.write")) return null;
        if (!requiresExactShaFileWrite(context)) return null;
        if (successfulAction(context, "workspace.file.write")) return null;

        String path = governedMutationPath(context);
        String sourceSha = exactRef(context);
        if (path.isBlank() || sourceSha.isBlank()) return null;
        return new CognitiveWorkerRuntime.Thought(
                "workspace.file.write",
                Map.of("path", path, "content", "source_sha=" + sourceSha + "\n"),
                "Work explicitly specifies one bounded file proof with exact source SHA; execute it through the governed workspace write action");
    }

    private static boolean requiresExactShaFileWrite(CognitiveWorkerRuntime.CognitiveContext context) {
        if (context.workSpec().consequence()
                != com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec.Consequence.MUTATING) return false;
        String path = governedMutationPath(context);
        if (path.isBlank()) return false;
        String text = workText(context).toLowerCase(java.util.Locale.ROOT);
        return !exactRef(context).isBlank()
                && (text.contains("create or replace only") || text.contains("write only"))
                && text.contains("exact utf-8 content:")
                && text.contains("source_sha=");
    }

    static CognitiveWorkerRuntime.Thought governedProjectPreparePrecondition(
            CognitiveWorkerRuntime.CognitiveContext context) {
        Objects.requireNonNull(context, "context");
        if (!hasMarker(context, GeneralWorkspacePhasePlanner.PHASE_PREPARE)
                || !hasMarker(context, GeneralWorkspacePhasePlanner.REQUIRE_MANIFEST)
                || !context.availableActions().contains("workspace.project.prepare")) return null;
        if (successfulAction(context, "workspace.project.prepare")
                || failedAction(context, "workspace.project.prepare")) return null;
        return new CognitiveWorkerRuntime.Thought(
                "workspace.project.prepare", Map.of(),
                "Deterministically prepare the minimal supported project scaffold from the carried work product");
    }

    static CognitiveWorkerRuntime.Thought governedDependencyPrecondition(
            CognitiveWorkerRuntime.CognitiveContext context) {
        Objects.requireNonNull(context, "context");
        if (!hasMarker(context, GeneralWorkspacePhasePlanner.PHASE_VERIFY)
                || !context.availableActions().contains("workspace.dependencies.install")
                || !"true".equalsIgnoreCase(context.memory().getOrDefault("workspaceDependencyInstallRequired", "false"))) {
            return null;
        }
        if (successfulAction(context, "workspace.dependencies.install")
                || failedAction(context, "workspace.dependencies.install")) return null;
        return new CognitiveWorkerRuntime.Thought(
                "workspace.dependencies.install", Map.of(),
                "Install dependencies from the carried project manifest before governed verification");
    }

    static CognitiveWorkerRuntime.Thought governedBuildPrecondition(
            CognitiveWorkerRuntime.CognitiveContext context) {
        Objects.requireNonNull(context, "context");
        if (!hasMarker(context, GeneralWorkspacePhasePlanner.PHASE_VERIFY)
                || !requiresGovernedBuild(context)
                || !context.availableActions().contains("workspace.build.run")) return null;
        if ("true".equalsIgnoreCase(context.memory().getOrDefault("workspaceDependencyInstallRequired", "false"))
                && !successfulAction(context, "workspace.dependencies.install")) return null;
        if (successfulAction(context, "workspace.build.run") || failedAction(context, "workspace.build.run")) return null;
        return new CognitiveWorkerRuntime.Thought(
                "workspace.build.run", Map.of(),
                "Run the governed project build after dependencies are ready");
    }

    static CognitiveWorkerRuntime.Thought governedRuntimePrecondition(
            CognitiveWorkerRuntime.CognitiveContext context) {
        Objects.requireNonNull(context, "context");
        if (!hasMarker(context, GeneralWorkspacePhasePlanner.PHASE_VERIFY)
                || !requiresRuntimeVerification(context)
                || !context.availableActions().contains("workspace.process.run")) return null;
        if (requiresGovernedBuild(context) && !successfulAction(context, "workspace.build.run")) return null;
        if (requiresGovernedTest(context) && !successfulAction(context, "workspace.test.run")) return null;
        if (successfulAction(context, "workspace.process.run") || failedAction(context, "workspace.process.run")) return null;

        String kind = context.memory().getOrDefault("workspaceProjectKind", "");
        if ("node-react".equals(kind)) {
            String script = "const http=require('http'),fs=require('fs');"
                    + "const body=fs.readFileSync('dist/index.html');"
                    + "const s=http.createServer((q,r)=>{r.statusCode=200;r.end(body)});"
                    + "s.listen(0,'127.0.0.1',()=>{const p=s.address().port;"
                    + "http.get({host:'127.0.0.1',port:p,path:'/'},res=>{let d='';"
                    + "res.on('data',c=>d+=c);res.on('end',()=>{if(res.statusCode!==200||!d.includes('root'))process.exitCode=1;s.close();});"
                    + "}).on('error',e=>{console.error(e);process.exitCode=1;s.close();});});";
            return new CognitiveWorkerRuntime.Thought(
                    "workspace.process.run",
                    Map.of("executable", "node", "argsJson", writeActionArgs(List.of("-e", script))),
                    "Run a bounded localhost HTTP probe against the built web artifact");
        }
        if ("node".equals(kind)) {
            // Root-cause fix (2026-09-22): plain (non-react) Node workspaces had no deterministic
            // runtime-check script, so this precondition always returned null here and cognition was
            // forced to freehand workspace.process.run's executable/argsJson from scratch every cycle.
            // The real LLM twice produced a malformed argsJson (failing ActionContractCatalog's
            // json-string-array validation identically), tripping the repeated-failure circuit breaker
            // and blocking the Objective at VERIFY. A generic Node entry point has no known HTTP port to
            // probe (unlike the node-react static-file-serving trick above), so instead this deterministically
            // spawns the entry point as a child process and treats an immediate crash/error as failure and
            // a process that is still alive after a bounded wait as success, then kills it.
            String entry = nodeEntryPoint(context);
            if (entry.isBlank()) return null;
            String script = "const cp=require('child_process');"
                    + "const c=cp.spawn('node',[" + jsonStringLiteral(entry) + "],{stdio:'ignore'});"
                    + "let crashed=false;"
                    + "c.on('error',()=>{crashed=true;});"
                    + "c.on('exit',(code)=>{if(code!==0)crashed=true;});"
                    + "setTimeout(()=>{try{c.kill();}catch(e){}process.exitCode=crashed?1:0;},1500);";
            return new CognitiveWorkerRuntime.Thought(
                    "workspace.process.run",
                    Map.of("executable", "node", "argsJson", writeActionArgs(List.of("-e", script))),
                    "Run a bounded process-start self-check against the produced Node entry point");
        }
        return null;
    }

    private static String jsonStringLiteral(String value) {
        try {
            return ACTION_INPUT_JSON.writeValueAsString(value);
        } catch (Exception impossible) {
            throw new IllegalStateException("cannot serialize governed script literal", impossible);
        }
    }

    private static String nodeEntryPoint(CognitiveWorkerRuntime.CognitiveContext context) {
        String manifest = context.memory().getOrDefault("workspaceProjectManifestPreview", "");
        Matcher start = NODE_START_SCRIPT.matcher(manifest);
        if (start.find()) {
            Matcher entry = NODE_START_ENTRY.matcher(start.group(1).trim());
            if (entry.find()) return entry.group(1);
        }
        Matcher main = NODE_MAIN_FIELD.matcher(manifest);
        if (main.find()) return main.group(1);
        return "";
    }

    private static boolean failedAction(CognitiveWorkerRuntime.CognitiveContext context, String actionRef) {
        return context.history().stream().anyMatch(cycle -> actionRef.equals(cycle.thought().actionRef())
                && !cycle.observation().success());
    }

    static CognitiveWorkerRuntime.Thought governedTestPrecondition(
            CognitiveWorkerRuntime.CognitiveContext context) {
        Objects.requireNonNull(context, "context");
        if (!context.availableActions().contains("workspace.test.run")) return null;
        if (!requiresGovernedTest(context)) return null;
        if (hasMarker(context, GeneralWorkspacePhasePlanner.PHASE_VERIFY)) {
            if ("true".equalsIgnoreCase(context.memory().getOrDefault("workspaceDependencyInstallRequired", "false"))
                    && !successfulAction(context, "workspace.dependencies.install")) return null;
            if (requiresGovernedBuild(context) && !successfulAction(context, "workspace.build.run")) return null;
        }
        // Root-cause fix (2026-09-22): this materialization gate is only meaningful when this Work
        // genuinely requires a materialized baseline before testing (an existing-repository objective).
        // requiresRepositoryMaterialization() already encodes exactly when that is true -- notably never
        // during phased VERIFY, since a phased Objective's materialization (if any) belongs exclusively
        // to its PRODUCE phase. The old unconditional check instead demanded materializationSatisfied()
        // for every phased VERIFY, including a genuinely fresh new-application Objective that correctly
        // never materializes anything: governedTestPrecondition then permanently returned null, so
        // cognition was asked to pick VERIFY's remaining action every cycle without ever being offered a
        // deterministic path to workspace.test.run, and the required-action completion guard kept
        // rejecting completion for a missing successful test that no deterministic path could ever supply.
        if (requiresRepositoryMaterialization(context) && !materializationSatisfied(context)) return null;
        if (!hasMarker(context, GeneralWorkspacePhasePlanner.PHASE_VERIFY)
                && context.workSpec().consequence()
                == com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec.Consequence.MUTATING
                && !hasWorkspaceSourceMutation(context)) return null;
        if (governedTestSatisfied(context)) return null;
        if (context.history().stream().anyMatch(cycle ->
                "workspace.test.run".equals(cycle.thought().actionRef()) && !cycle.observation().success())) {
            // Never deterministically repeat a failed test. Dedicated READ_ONLY verification steps have
            // no step-local mutation index, so the older mutation-relative guard could otherwise rerun
            // the same failing suite until the full cognitive cycle budget was exhausted.
            return null;
        }
        if (testAttemptedAfterLatestMutation(context)) {
            // A failed verification after the latest mutation must return control to cognition so it can
            // inspect the failure and modify the work product instead of looping the same test forever.
            return null;
        }
        if (!hasMarker(context, GeneralWorkspacePhasePlanner.PHASE_VERIFY)
                && context.workSpec().consequence()
                == com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec.Consequence.MUTATING
                && governedMutationPath(context).isBlank()) {
            // Vague/unknown engineering work may live in a nested project. Completion still requires a
            // successful governed test after the latest mutation, but cognition must choose the correct
            // workingDirectory instead of a deterministic root-level test that can verify the wrong project.
            return null;
        }
        return new CognitiveWorkerRuntime.Thought(
                "workspace.test.run", Map.of(),
                "Run the governed repository test suite against the latest Objective-workspace source mutation before commit/publication");
    }

    private static boolean testAttemptedAfterLatestMutation(CognitiveWorkerRuntime.CognitiveContext context) {
        int latestMutation = -1;
        int latestTest = -1;
        for (int i = 0; i < context.history().size(); i++) {
            String action = context.history().get(i).thought().actionRef();
            if ("workspace.file.write".equals(action)
                    || "workspace.file.patch".equals(action)
                    || "workspace.shell.run".equals(action)
                    || "workspace.process.run".equals(action)) latestMutation = i;
            if ("workspace.test.run".equals(action)) latestTest = i;
        }
        return latestMutation >= 0 && latestTest > latestMutation;
    }

    static CognitiveWorkerRuntime.Thought governedGitPrecondition(
            CognitiveWorkerRuntime.CognitiveContext context) {
        Objects.requireNonNull(context, "context");
        if (!context.availableActions().contains("workspace.git.run")) return null;
        if (requiresGitInitialization(context)) {
            return new CognitiveWorkerRuntime.Thought(
                    "workspace.git.run",
                    Map.of("argsJson", writeActionArgs(List.of("init"))),
                    "Initialize local Git deterministically before staging a carried fresh workspace");
        }
        if (requiresGitAdd(context) && !successfulGitSubcommand(context, "add")) {
            String path = governedMutationPath(context);
            if (!hasWorkspaceSourceMutation(context) && path.isBlank()
                    && !hasMarker(context, GeneralWorkspacePhasePlanner.PHASE_DELIVER)) return null;
            if (requiresGovernedTest(context) && !governedTestSatisfied(context)) return null;
            List<String> args = path.isBlank() ? List.of("add", "-A") : List.of("add", path);
            return new CognitiveWorkerRuntime.Thought(
                    "workspace.git.run",
                    Map.of("argsJson", writeActionArgs(args)),
                    path.isBlank()
                            ? "Stage the complete bounded Objective-workspace source delta before the required commit"
                            : "Stage the governed target before the required commit");
        }
        if (requiresGitCommit(context)
                && (!requiresGitAdd(context) || successfulGitSubcommand(context, "add"))
                && !successfulGitSubcommand(context, "commit")) {
            return new CognitiveWorkerRuntime.Thought(
                    "workspace.git.run",
                    Map.of("argsJson", writeActionArgs(List.of(
                            "commit", "-m", "Complete governed Objective work step"))),
                    "Create one immutable local Git commit for the verified Objective work product");
        }
        if (requiresGitVerification(context)
                && context.availableActions().contains("workspace.git.status")
                && successfulGitSubcommand(context, "commit")
                && !successfulAction(context, "workspace.git.status")) {
            return new CognitiveWorkerRuntime.Thought(
                    "workspace.git.status", Map.of(),
                    "Inspect immutable local HEAD and changed paths after the required commit");
        }
        return null;
    }

    static CognitiveWorkerRuntime.Thought governedRemoteProposalPrecondition(
            CognitiveWorkerRuntime.CognitiveContext context) {
        Objects.requireNonNull(context, "context");
        if (!requiresRemoteProposal(context)) return null;
        if (!context.availableActions().contains("workspace.github.pr.publish")) return null;
        if (requiresWorkspaceSourceMutation(context)
                && successfulAction(context, "workspace.repository.materialize")
                && !hasWorkspaceSourceMutation(context)) return null;
        // In a fresh publish-only Work step, prior commit history is intentionally not step-local.
        // The publisher itself re-opens the durable Objective workspace and fails closed unless a clean
        // committed delta exists. When this same step mutated source, however, force add/test/commit first.
        if (hasWorkspaceSourceMutation(context) && !successfulGitSubcommand(context, "commit")) return null;
        if (requiresGovernedTest(context) && hasWorkspaceSourceMutation(context) && !governedTestSatisfied(context)) return null;
        if (successfulAction(context, "workspace.github.pr.publish")) return null;
        return new CognitiveWorkerRuntime.Thought(
                "workspace.github.pr.publish", Map.of(),
                "Publish the clean tested committed Objective-workspace delta through the governed credential-isolated proposal action");
    }

    /**
     * A governed repository-shaped target() is a governance/authority-discovery resource identity, not
     * automatic proof that an existing GitHub source repository must be checked out: after the #465
     * authority-target fix, target() is always repository-shaped, including for a brand-new application
     * that has no existing source yet. Explicit materialize/snapshot/checkout/exact-SHA intent in the
     * Work text always requires real materialization and fails closed if the source cannot be obtained
     * -- that governs regardless of repository-shape.
     *
     * <p>Root-cause fix (2026-09-23, Founder-reported): a Work the planner has marked as fresh
     * new-application work (NEW_APPLICATION_WORKSPACE_EVIDENCE) previously skipped materialization
     * entirely, because the derived destination repository did not exist yet and materializing a
     * nonexistent repository always failed closed with a 404. That made {@code .metatron-repository}
     * provenance -- the one thing {@link com.metatron.workforce.runtime.GitHubWorkspaceProposalPublisher}
     * requires to open a reviewable PR -- impossible to ever establish for exactly the kind of Work whose
     * completion the Human most needs to see: a completed Objective had no possible path to visible
     * output. Materialization is now required here too; {@link #repositoryMaterializationPrecondition}
     * passes {@code createIfMissing=true} only for this fresh-application case, so the destination
     * repository is created (empty, auto-initialized) before it is materialized. An explicitly-named
     * existing repository is unaffected: it still materializes and fails closed exactly as before, and
     * never auto-creates on a typo.</p>
     */
    private static boolean requiresRepositoryMaterialization(CognitiveWorkerRuntime.CognitiveContext context) {
        if (phased(context)
                && !hasMarker(context, GeneralWorkspacePhasePlanner.PHASE_PRODUCE)) return false;
        if (context.memory().getOrDefault("workspaceMaterialized", "false").equalsIgnoreCase("true")) return false;
        String text = workText(context).toLowerCase(java.util.Locale.ROOT);
        boolean explicitMaterializationIntent = text.contains("materializ")
                || text.contains("snapshot")
                || text.contains("checkout")
                || text.contains("source tree")
                || text.contains("git rev-parse")
                || EXACT_GIT_SHA.matcher(text).find();
        if (explicitMaterializationIntent) return true;
        return !repositoryFromTarget(context.workSpec().target()).isBlank();
    }

    /**
     * Sentinel evidence-requirement token the planner attaches (see
     * FounderWorkerExecutionPlanProposalService) when it derived a brand-new application's repository
     * target itself, rather than the Objective naming an existing repository. Mirrors the existing
     * research-action:research.web.search evidenceRequirements convention: a structured planner-to-brain
     * signal carried through the existing contract, not a new ExecutionWorkSpec field.
     */
    static final String NEW_APPLICATION_WORKSPACE_EVIDENCE = "workspace-source:fresh-new-application";

    private static boolean isFreshNewApplicationWork(CognitiveWorkerRuntime.CognitiveContext context) {
        return context.workSpec().evidenceRequirements().stream()
                .anyMatch(NEW_APPLICATION_WORKSPACE_EVIDENCE::equalsIgnoreCase);
    }

    private static boolean materializationSatisfied(CognitiveWorkerRuntime.CognitiveContext context) {
        if (context.memory().getOrDefault("workspaceMaterialized", "false").equalsIgnoreCase("true")) return true;
        if (!context.availableActions().contains("workspace.repository.materialize")) return true;
        return successfulAction(context, "workspace.repository.materialize");
    }

    private static boolean hasWorkspaceSourceMutation(CognitiveWorkerRuntime.CognitiveContext context) {
        return context.history().stream().anyMatch(cycle -> cycle.observation().success()
                && ("workspace.file.write".equals(cycle.thought().actionRef())
                || "workspace.file.patch".equals(cycle.thought().actionRef())
                || "workspace.shell.run".equals(cycle.thought().actionRef())
                || "workspace.process.run".equals(cycle.thought().actionRef())));
    }

    private static boolean hasWorkspaceWorkProductMutation(CognitiveWorkerRuntime.CognitiveContext context) {
        return context.history().stream().anyMatch(cycle -> {
            if (!cycle.observation().success()) return false;
            String action = cycle.thought().actionRef();
            if ("workspace.shell.run".equals(action) || "workspace.process.run".equals(action)) return true;
            if (!"workspace.file.write".equals(action) && !"workspace.file.patch".equals(action)) return false;
            return !isProjectManifestPath(cycle.thought().inputs().getOrDefault("path", ""));
        });
    }

    private static boolean governedTestSatisfied(CognitiveWorkerRuntime.CognitiveContext context) {
        if (!requiresGovernedTest(context)) return true;
        int latestMutation = -1;
        int latestTest = -1;
        for (int i = 0; i < context.history().size(); i++) {
            CognitiveWorkerRuntime.Cycle cycle = context.history().get(i);
            if (!cycle.observation().success()) continue;
            String action = cycle.thought().actionRef();
            if ("workspace.file.write".equals(action)
                    || "workspace.file.patch".equals(action)
                    || "workspace.shell.run".equals(action)
                    || "workspace.process.run".equals(action)) latestMutation = i;
            if ("workspace.test.run".equals(action)) latestTest = i;
        }
        if (hasMarker(context, GeneralWorkspacePhasePlanner.PHASE_VERIFY)) {
            return latestTest >= 0;
        }
        if (context.workSpec().consequence()
                == com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec.Consequence.MUTATING) {
            return latestMutation >= 0 && latestTest > latestMutation;
        }
        return latestTest >= 0;
    }

    private static boolean requiresRemoteProposal(CognitiveWorkerRuntime.CognitiveContext context) {
        if (phased(context)) {
            return hasMarker(context, GeneralWorkspacePhasePlanner.PHASE_DELIVER)
                    && hasMarker(context, GeneralWorkspacePhasePlanner.REQUIRE_GITHUB_PR);
        }
        if (context.workSpec().consequence()
                != com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec.Consequence.MUTATING) return false;
        String text = workText(context).toLowerCase(java.util.Locale.ROOT);
        return text.contains("pull request")
                || text.contains("open pr")
                || text.contains("proposal branch")
                || text.contains("publish") && text.contains("github");
    }

    static List<String> researchCompletionQualityProblems(
            CognitiveWorkerRuntime.CognitiveContext context,
            ActionFabric.ActionObservation latest,
            String summary) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(latest, "latest");
        int requested = requestedResearchItemCount(context);
        if (requested < 2) return List.of();

        LinkedHashSet<String> observedUrls = new LinkedHashSet<>();
        for (CognitiveWorkerRuntime.Cycle cycle : context.history()) {
            collectHttpEvidence(observedUrls, cycle.observation().evidenceReferences());
        }
        collectHttpEvidence(observedUrls, latest.evidenceReferences());

        LinkedHashSet<String> summaryUrls = new LinkedHashSet<>();
        Matcher urls = RESEARCH_URL.matcher(summary == null ? "" : summary);
        while (urls.find()) summaryUrls.add(urls.group());

        int matchedSummaryUrls = 0;
        for (String url : summaryUrls) if (observedUrls.contains(url)) matchedSummaryUrls++;

        int itemCount = countMatches(RESEARCH_ITEM, summary);
        int decisionCount = countMatches(RESEARCH_DECISION, summary);
        List<String> problems = new ArrayList<>();
        if (observedUrls.size() < requested) {
            problems.add("at least " + requested + " distinct attributable research sources (observed "
                    + observedUrls.size() + ")");
        }
        if (itemCount < requested) {
            problems.add("an enumerated " + requested + "-item substantive deliverable (found " + itemCount + " items)");
        }
        if (matchedSummaryUrls < requested) {
            problems.add(requested + " observed source URLs embedded in the deliverable (found "
                    + matchedSummaryUrls + ")");
        }
        String work = workText(context).toLowerCase(java.util.Locale.ROOT);
        if ((work.contains("keep") || work.contains("test") || work.contains("change") || work.contains("reject"))
                && decisionCount < requested) {
            problems.add(requested + " explicit KEEP/TEST/CHANGE/REJECT decisions (found " + decisionCount + ")");
        }
        return List.copyOf(problems);
    }

    static int requestedResearchItemCount(CognitiveWorkerRuntime.CognitiveContext context) {
        Matcher matcher = RESEARCH_TOP_N.matcher(workText(context));
        int requested = 0;
        while (matcher.find()) {
            try {
                int value = Integer.parseInt(matcher.group(1));
                if (value > requested && value <= 50) requested = value;
            } catch (NumberFormatException ignored) {
                // Ignore malformed count and leave completion to normal acceptance reasoning.
            }
        }
        return requested;
    }

    private static int countMatches(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value == null ? "" : value);
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }

    private static void collectHttpEvidence(LinkedHashSet<String> out, List<String> refs) {
        if (refs == null) return;
        for (String ref : refs) {
            if (ref != null && (ref.startsWith("https://") || ref.startsWith("http://"))) out.add(ref.trim());
        }
    }

    private static boolean requiresExternalResearch(CognitiveWorkerRuntime.CognitiveContext context) {
        if (!context.availableActions().contains(GeneralWebResearchAction.ACTION_REF)) return false;
        boolean explicitMarker = context.workSpec().evidenceRequirements().stream()
                .map(value -> value.toLowerCase(java.util.Locale.ROOT))
                .anyMatch(value -> value.contains("research.web.search")
                        || value.contains("requested-capability:") && value.contains("research"));
        if (explicitMarker) return true;
        String semantic = workText(context).toLowerCase(java.util.Locale.ROOT);
        boolean researchIntent = semantic.contains("research") || semantic.contains("paper")
                || semantic.contains("publication") || semantic.contains("report")
                || semantic.contains("standard") || semantic.contains("regulator")
                || semantic.contains("regulatory");
        boolean externalEvidence = semantic.contains("source") || semantic.contains("evidence")
                || semantic.contains("external") || semantic.contains("web")
                || semantic.contains("internet") || semantic.contains("recent")
                || semantic.contains("current") || semantic.contains("new ");
        return researchIntent && externalEvidence;
    }

    private static String workText(CognitiveWorkerRuntime.CognitiveContext context) {
        return context.workSpec().objective() + " "
                + context.workSpec().target() + " "
                + String.join(" ", context.workSpec().acceptanceCriteria()) + " "
                + String.join(" ", context.workSpec().evidenceRequirements());
    }

    private static boolean hasMarker(CognitiveWorkerRuntime.CognitiveContext context, String marker) {
        return context.workSpec().evidenceRequirements().stream().anyMatch(marker::equalsIgnoreCase);
    }

    private static boolean phased(CognitiveWorkerRuntime.CognitiveContext context) {
        return hasMarker(context, GeneralWorkspacePhasePlanner.PHASE_PRODUCE)
                || hasMarker(context, GeneralWorkspacePhasePlanner.PHASE_PREPARE)
                || hasMarker(context, GeneralWorkspacePhasePlanner.PHASE_VERIFY)
                || hasMarker(context, GeneralWorkspacePhasePlanner.PHASE_DELIVER);
    }

    private static boolean requiresGovernedTest(CognitiveWorkerRuntime.CognitiveContext context) {
        if (phased(context)) {
            return hasMarker(context, GeneralWorkspacePhasePlanner.PHASE_VERIFY)
                    && hasMarker(context, GeneralWorkspacePhasePlanner.REQUIRE_TEST);
        }
        String text = workText(context).toLowerCase(java.util.Locale.ROOT);
        return text.contains("test suite")
                || text.contains("run test")
                || text.contains("execute test")
                || text.contains("tests pass")
                || text.contains("test action")
                || text.contains("tests,")
                || text.contains("workspace.test.run");
    }

    /** Mirrors the existing "build"/"compile" phrase convention already used by explicitlyRequiresAction(). */
    private static boolean requiresGovernedBuild(CognitiveWorkerRuntime.CognitiveContext context) {
        if (phased(context)) {
            return hasMarker(context, GeneralWorkspacePhasePlanner.PHASE_VERIFY)
                    && hasMarker(context, GeneralWorkspacePhasePlanner.REQUIRE_BUILD);
        }
        if (context.workSpec().consequence()
                != com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec.Consequence.MUTATING) return false;
        String text = workText(context).toLowerCase(java.util.Locale.ROOT);
        return text.contains("build") || text.contains("compile") || text.contains("workspace.build.run");
    }

    private static boolean requiresRuntimeVerification(CognitiveWorkerRuntime.CognitiveContext context) {
        if (phased(context)) {
            return hasMarker(context, GeneralWorkspacePhasePlanner.PHASE_VERIFY)
                    && hasMarker(context, GeneralWorkspacePhasePlanner.REQUIRE_RUNTIME);
        }
        if (context.workSpec().consequence()
                != com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec.Consequence.MUTATING) return false;
        String text = workText(context).toLowerCase(java.util.Locale.ROOT);
        return text.contains("runtime verification")
                || text.contains("verify the runtime")
                || text.contains("runtime observation")
                || text.contains("workspace.process.run");
    }

    private static boolean successfulAction(CognitiveWorkerRuntime.CognitiveContext context, String actionRef) {
        return context.history().stream().anyMatch(cycle ->
                actionRef.equals(cycle.thought().actionRef()) && cycle.observation().success());
    }

    private static boolean failedBefore(CognitiveWorkerRuntime.CognitiveContext context, String actionRef) {
        return context.history().stream().anyMatch(cycle ->
                actionRef.equals(cycle.thought().actionRef()) && !cycle.observation().success());
    }

    private static boolean requiresGitInitialization(CognitiveWorkerRuntime.CognitiveContext context) {
        if (!hasMarker(context, GeneralWorkspacePhasePlanner.PHASE_DELIVER)) return false;
        if (!requiresGitCommit(context) && !requiresRemoteProposal(context)) return false;
        if ("true".equalsIgnoreCase(context.memory().getOrDefault("workspaceGitInitialized", "false"))) return false;
        return !successfulGitSubcommand(context, "init");
    }

    private static boolean requiresGitAdd(CognitiveWorkerRuntime.CognitiveContext context) {
        if (phased(context)) {
            return hasMarker(context, GeneralWorkspacePhasePlanner.PHASE_DELIVER)
                    && (hasMarker(context, GeneralWorkspacePhasePlanner.REQUIRE_GIT_COMMIT)
                    || hasMarker(context, GeneralWorkspacePhasePlanner.REQUIRE_GITHUB_PR));
        }
        if (context.workSpec().consequence()
                != com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec.Consequence.MUTATING) return false;
        String text = workText(context).toLowerCase(java.util.Locale.ROOT);
        return requiresGitCommit(context)
                || text.contains("stage ") || text.contains("staged ") || text.contains("git add");
    }

    private static boolean requiresGitCommit(CognitiveWorkerRuntime.CognitiveContext context) {
        if (phased(context)) {
            return hasMarker(context, GeneralWorkspacePhasePlanner.PHASE_DELIVER)
                    && (hasMarker(context, GeneralWorkspacePhasePlanner.REQUIRE_GIT_COMMIT)
                    || hasMarker(context, GeneralWorkspacePhasePlanner.REQUIRE_GITHUB_PR));
        }
        if (context.workSpec().consequence()
                != com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec.Consequence.MUTATING) return false;
        if (requiresRemoteProposal(context)) return true;
        String text = workText(context).toLowerCase(java.util.Locale.ROOT);
        return text.contains("local git commit")
                || text.contains("local commit")
                || text.contains("create one commit")
                || text.contains("create a commit")
                || text.contains("commit exists")
                || text.contains("commit the ")
                || text.contains("commit message")
                || text.contains("git evidence");
    }

    private static boolean requiresGitVerification(CognitiveWorkerRuntime.CognitiveContext context) {
        if (phased(context)) {
            return hasMarker(context, GeneralWorkspacePhasePlanner.PHASE_DELIVER)
                    && hasMarker(context, GeneralWorkspacePhasePlanner.REQUIRE_GIT_VERIFY);
        }
        if (!requiresGitCommit(context)) return false;
        String text = workText(context).toLowerCase(java.util.Locale.ROOT);
        return text.contains("git show")
                || text.contains("git status")
                || text.contains("git verification")
                || text.contains("verify git")
                || text.contains("verify the commit")
                || text.contains("verify commit")
                || text.contains("commit exists")
                || text.contains("exactly the change")
                || text.contains("inspect immutable local head");
    }

    private static String governedMutationPath(CognitiveWorkerRuntime.CognitiveContext context) {
        ExactTextReplacement replacement = exactTextReplacement(context);
        if (replacement != null) return replacement.path();
        String direct = governedStagePath(context.workSpec().target());
        if (!direct.isBlank()) return direct;
        Matcher matcher = WORKSPACE_FILE_PATH.matcher(workText(context));
        while (matcher.find()) {
            String candidate = matcher.group(1).trim();
            if (safeWorkspaceMutationPath(candidate)) return candidate;
        }
        return "";
    }

    private static boolean requiresWorkspaceSourceMutation(CognitiveWorkerRuntime.CognitiveContext context) {
        if (phased(context)) {
            return hasMarker(context, GeneralWorkspacePhasePlanner.PHASE_PRODUCE);
        }
        if (context.workSpec().consequence()
                != com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec.Consequence.MUTATING) return false;
        if (exactTextReplacement(context) != null) return true;
        String text = " " + workText(context).toLowerCase(java.util.Locale.ROOT) + " ";
        return text.contains(" repair ")
                || text.contains(" replace ")
                || text.contains(" modify ")
                || text.contains(" edit ")
                || text.contains(" change ")
                || text.contains(" create ")
                || text.contains(" delete ")
                || text.contains(" remove ")
                || text.contains(" write ");
    }

    private static String governedStagePath(String target) {
        String path = stripRepositoryTargetPrefix(target);
        if (path.isBlank() || path.startsWith("/") || path.contains("\\") || path.contains("..")
                || OWNER_REPOSITORY.matcher(path).matches()) return "";
        return path;
    }

    private static boolean successfulGitSubcommand(CognitiveWorkerRuntime.CognitiveContext context, String subcommand) {
        return context.history().stream().anyMatch(cycle ->
                cycle.observation().success() && gitSubcommand(cycle.thought(), subcommand));
    }

    private static boolean gitSubcommand(CognitiveWorkerRuntime.Thought thought, String subcommand) {
        if (!"workspace.git.run".equals(thought.actionRef())) return false;
        try {
            List<String> args = ACTION_INPUT_JSON.readValue(
                    thought.inputs().getOrDefault("argsJson", "[]"),
                    new TypeReference<List<String>>() {});
            return !args.isEmpty() && subcommand.equals(args.getFirst());
        } catch (Exception invalid) {
            return false;
        }
    }

    private static String writeActionArgs(List<String> args) {
        try {
            return ACTION_INPUT_JSON.writeValueAsString(args);
        } catch (Exception impossible) {
            throw new IllegalStateException("cannot serialize governed action arguments", impossible);
        }
    }

    private static String exactRef(CognitiveWorkerRuntime.CognitiveContext context) {
        Matcher matcher = EXACT_GIT_SHA.matcher(workText(context));
        return matcher.find() ? matcher.group().toLowerCase(java.util.Locale.ROOT) : "";
    }

    private static String repositoryFromTarget(String target) {
        String repository = stripRepositoryTargetPrefix(target);
        int at = repository.indexOf('@');
        if (at > 0) repository = repository.substring(0, at);
        return OWNER_REPOSITORY.matcher(repository).matches() ? repository : "";
    }

    /**
     * The governed target for General Workspace work is the canonical "repository:owner/repo" shape
     * SotDiscoveryService/AuthorityManifestCatalog resolve (matching its repository:* authority
     * wildcard); this recovers the bare "owner/repo" repository locator the brain itself operates on.
     * A bare "owner/repo" target (still produced by some deterministic planning paths) passes through
     * unchanged.
     */
    private static String stripRepositoryTargetPrefix(String target) {
        String value = target == null ? "" : target.trim();
        return value.startsWith("repository:") ? value.substring("repository:".length()) : value;
    }

    private static boolean exactSourceMaterializationWork(CognitiveWorkerRuntime.CognitiveContext context) {
        if (context.workSpec().consequence()
                != com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec.Consequence.READ_ONLY) return false;
        if (!"execution.general.workspace".equals(context.workSpec().requiredCapability())) return false;
        if (exactRef(context).isBlank()) return false;
        String objective = context.workSpec().objective().toLowerCase(java.util.Locale.ROOT);
        return objective.contains("materializ") || objective.contains("snapshot") || objective.contains("checkout");
    }

    static CognitiveWorkerRuntime.Reflection exactSourceMaterializationReflection(
            CognitiveWorkerRuntime.CognitiveContext context,
            ActionFabric.ActionObservation observation) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(observation, "observation");
        if (!"workspace.repository.materialize".equals(observation.actionRef()) || !observation.success()) return null;
        if (!exactSourceMaterializationWork(context)) return null;

        String expectedSha = exactRef(context);
        String expectedRepository = repositoryFromTarget(context.workSpec().target());
        String actualSha = observation.outputs().getOrDefault("sourceCommitSha", "").trim().toLowerCase(java.util.Locale.ROOT);
        String actualRepository = observation.outputs().getOrDefault("repository", "").trim();
        String workspaceRef = observation.outputs().getOrDefault("workspaceRef", "").trim();
        String localBaseline = observation.outputs().getOrDefault("localBaselineCommitSha", "").trim();
        int materializedFiles;
        try {
            materializedFiles = Integer.parseInt(observation.outputs().getOrDefault("materializedFiles", "0"));
        } catch (NumberFormatException invalid) {
            materializedFiles = 0;
        }

        if (!expectedSha.equals(actualSha)) {
            return CognitiveWorkerRuntime.Reflection.failed(
                    "Materialized source identity mismatch: expected sourceCommitSha=" + expectedSha
                            + " observed=" + actualSha);
        }
        if (!expectedRepository.isBlank() && !expectedRepository.equals(actualRepository)) {
            return CognitiveWorkerRuntime.Reflection.failed(
                    "Materialized repository mismatch: expected=" + expectedRepository
                            + " observed=" + actualRepository);
        }
        if (!workspaceRef.startsWith("execution-workspace:") || materializedFiles < 1) {
            return CognitiveWorkerRuntime.Reflection.failed(
                    "Materialization evidence is incomplete: workspaceRef/files do not prove an accessible source snapshot");
        }
        if (requiresGovernedTest(context)) {
            return CognitiveWorkerRuntime.Reflection.continueWith(
                    "Exact source snapshot proven by materialization provenance; governed test execution remains required");
        }
        return CognitiveWorkerRuntime.Reflection.complete(
                "Exact source snapshot proven by materialization provenance: sourceCommitSha=" + expectedSha
                        + "; localBaselineCommitSha=" + localBaseline
                        + " is isolated workspace identity and is intentionally distinct from source identity");
    }

    @Override
    public boolean blocksCompletionForUnresolvedFailure(
            CognitiveWorkerRuntime.CognitiveContext context,
            String actionRef) {
        Objects.requireNonNull(context, "context");
        String ref = actionRef == null ? "" : actionRef.trim();

        // General engineering often uses process/shell actions as disposable diagnostics while
        // locating an unfamiliar defect. A failed diagnostic must remain in evidence, but once
        // the actual acceptance prerequisites (source mutation, governed tests, Git/PR, etc.) are
        // independently satisfied it must not keep the Objective alive forever or force duplicate PRs.
        if ("workspace.process.run".equals(ref)) {
            return explicitlyRequiresAction(context, ref, "process execution", "run process");
        }
        if ("workspace.shell.run".equals(ref)) {
            return explicitlyRequiresAction(context, ref, "shell execution", "run shell");
        }
        if ("workspace.file.list".equals(ref)) {
            return explicitlyRequiresAction(context, ref, "list files", "file listing");
        }
        if ("workspace.git.diff".equals(ref)) {
            return explicitlyRequiresAction(context, ref, "git diff");
        }
        if ("workspace.dependencies.install".equals(ref)) {
            return explicitlyRequiresAction(context, ref, "install dependencies", "dependency installation");
        }

        // Build is optional for many test-driven fixes, but required when the Work explicitly
        // asks for build evidence.
        if ("workspace.build.run".equals(ref)) {
            return explicitlyRequiresAction(context, ref, "build", "compile");
        }

        // All other action failures remain fail-closed by default. This preserves the existing
        // safety invariant for required materialization, inspection, mutation, tests, Git commit,
        // publication, research, and any future unknown action.
        return true;
    }

    private static boolean explicitlyRequiresAction(
            CognitiveWorkerRuntime.CognitiveContext context,
            String actionRef,
            String... semanticPhrases) {
        StringBuilder text = new StringBuilder();
        text.append(context.workSpec().objective()).append('\n')
                .append(context.workSpec().target()).append('\n');
        context.workSpec().acceptanceCriteria().forEach(value -> text.append(value).append('\n'));
        context.workSpec().evidenceRequirements().forEach(value -> text.append(value).append('\n'));
        String normalized = text.toString().toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains(actionRef.toLowerCase(java.util.Locale.ROOT))) return true;
        for (String phrase : semanticPhrases) {
            if (phrase != null && !phrase.isBlank()
                    && normalized.contains(phrase.toLowerCase(java.util.Locale.ROOT))) return true;
        }
        return false;
    }

    @Override
    public CognitiveWorkerRuntime.Reflection reflect(CognitiveWorkerRuntime.CognitiveContext context,
                                                      ActionFabric.ActionObservation observation) {
        CognitiveWorkerRuntime.Reflection canonicalMaterialization =
                exactSourceMaterializationReflection(context, observation);
        if (canonicalMaterialization != null) return canonicalMaterialization;
        CognitiveWorkerRuntime.Reflection requiredAction = governedRequiredActionReflection(context, observation);
        if (requiredAction != null) {
            return enforceRequiredActionCompletion(context, observation, requiredAction);
        }

        CognitiveWorkerRuntime.Reflection deterministic = deterministicNonTerminalReflection(context, observation);
        if (deterministic != null) return deterministic;

        String system = REFLECTION_SYSTEM;
        String user = contextPrompt(context) + latestObservationPrompt(observation);
        CognitiveProviderResult providerResult = completeObject(context, system, user);
        Map<String, Object> parsed = providerResult.parsed();
        String decision = text(parsed.get("decision"), "decision").toUpperCase(java.util.Locale.ROOT);
        String summary = text(parsed.get("summary"), "summary");
        CognitiveWorkerRuntime.Reflection proposed = switch (decision) {
            case "CONTINUE" -> CognitiveWorkerRuntime.Reflection.continueWith(summary);
            case "COMPLETE" -> CognitiveWorkerRuntime.Reflection.complete(summary);
            case "FAILED" -> CognitiveWorkerRuntime.Reflection.failed(summary);
            default -> throw new IllegalStateException("invalid cognitive reflection decision: " + decision);
        };
        return enforceRequiredActionCompletion(context, observation, proposed);
    }

    /**
     * Avoid spending scarce Intelligence on a reflection whose only safe result is CONTINUE.
     *
     * Failed tool observations remain recoverable by inspection/change/retry inside the bounded
     * Cognitive Worker loop. For successful intermediate actions, reuse the same completion guard
     * that fences provider-proposed COMPLETE: when that guard proves mandatory governed evidence
     * is still missing, there is no reason to ask an LLM whether the Work is complete.
     *
     * External research is intentionally excluded because synthesis/quality judgment is itself
     * substantive Work and may require Intelligence.
     */
    static CognitiveWorkerRuntime.Reflection deterministicNonTerminalReflection(
            CognitiveWorkerRuntime.CognitiveContext context,
            ActionFabric.ActionObservation observation) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(observation, "observation");

        if (!observation.success()) {
            // Anti-livelock backstop: workspace.repository.materialize is a deterministic required
            // action (see repositoryMaterializationPrecondition) that always produces the same inputs
            // for the same Work state, so a second failure of it is not a recoverable diagnostic
            // opportunity like a failed test/build/patch can be -- it is the same immutable-baseline
            // request failing again. Rather than let cognition keep proposing the identical action for
            // the entire cycle budget, terminate truthfully with the actual failure once it has already
            // failed before, exactly as required by a genuinely required-but-unobtainable source.
            if ("workspace.repository.materialize".equals(observation.actionRef())
                    && failedBefore(context, observation.actionRef())) {
                return CognitiveWorkerRuntime.Reflection.failed(
                        "Governed action " + observation.actionRef()
                                + " failed again with no intervening state change; bounded recovery is not possible: "
                                + clean(observation.summary()));
            }
            return CognitiveWorkerRuntime.Reflection.continueWith(
                    "Governed action " + observation.actionRef()
                            + " failed; inspect the observed failure, change or diagnose state, then retry only when justified: "
                            + clean(observation.summary()));
        }
        if (GeneralWebResearchAction.ACTION_REF.equals(observation.actionRef())) return null;

        CognitiveWorkerRuntime.Reflection guarded = enforceRequiredActionCompletion(
                context,
                observation,
                CognitiveWorkerRuntime.Reflection.complete("intermediate governed action observed"));
        if (guarded.decision() == CognitiveWorkerRuntime.Decision.CONTINUE) {
            return CognitiveWorkerRuntime.Reflection.continueWith(
                    "Governed action " + observation.actionRef()
                            + " succeeded; mandatory completion evidence is still missing. "
                            + guarded.summary());
        }
        return null;
    }

    static CognitiveWorkerRuntime.Reflection governedRequiredActionReflection(
            CognitiveWorkerRuntime.CognitiveContext context,
            ActionFabric.ActionObservation observation) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(observation, "observation");
        if (!observation.success()) return null;
        if (hasMarker(context, GeneralWorkspacePhasePlanner.PHASE_PRODUCE)
                && ("workspace.file.write".equals(observation.actionRef())
                || "workspace.file.patch".equals(observation.actionRef())
                || "workspace.shell.run".equals(observation.actionRef())
                || "workspace.process.run".equals(observation.actionRef()))) {
            return CognitiveWorkerRuntime.Reflection.complete(
                    "Production phase emitted governed workspace work-product evidence");
        }
        if (hasMarker(context, GeneralWorkspacePhasePlanner.PHASE_PREPARE)
                && "workspace.project.prepare".equals(observation.actionRef())) {
            return CognitiveWorkerRuntime.Reflection.complete(
                    "Preparation phase emitted governed deterministic project scaffold evidence");
        }
        if (hasMarker(context, GeneralWorkspacePhasePlanner.PHASE_VERIFY)
                && ("workspace.build.run".equals(observation.actionRef())
                || "workspace.test.run".equals(observation.actionRef())
                || "workspace.process.run".equals(observation.actionRef()))) {
            return CognitiveWorkerRuntime.Reflection.complete(
                    "Verification phase emitted governed verification evidence");
        }
        if (requiresExactShaFileWrite(context) && "workspace.file.write".equals(observation.actionRef())) {
            return CognitiveWorkerRuntime.Reflection.complete(
                    "Governed workspace file write succeeded with the explicit exact-SHA proof content");
        }
        if (requiresGovernedTest(context) && "workspace.test.run".equals(observation.actionRef())) {
            return CognitiveWorkerRuntime.Reflection.complete(
                    "Governed test action succeeded against the latest observed source mutation");
        }
        if (requiresRemoteProposal(context) && "workspace.github.pr.publish".equals(observation.actionRef())) {
            return CognitiveWorkerRuntime.Reflection.complete(
                    "Governed remote publication succeeded with a reviewable unmerged Pull Request");
        }
        if ("workspace.git.run".equals(observation.actionRef())) {
            if (requiresGitAdd(context) && !successfulGitSubcommand(context, "add")) {
                return CognitiveWorkerRuntime.Reflection.continueWith(
                        "Governed staging succeeded; the required local commit remains");
            }
            if (requiresGitCommit(context) && !successfulGitSubcommand(context, "commit")) {
                return requiresGitVerification(context)
                        ? CognitiveWorkerRuntime.Reflection.continueWith(
                                "Governed local commit succeeded; immutable Git verification remains")
                        : CognitiveWorkerRuntime.Reflection.complete("Governed local commit succeeded");
            }
        }
        if (requiresGitVerification(context)
                && successfulGitSubcommand(context, "commit")
                && "workspace.git.status".equals(observation.actionRef())) {
            return CognitiveWorkerRuntime.Reflection.complete(
                    "Immutable local Git HEAD and recent history were inspected after commit");
        }
        return null;
    }

    static CognitiveWorkerRuntime.Reflection enforceRequiredActionCompletion(
            CognitiveWorkerRuntime.CognitiveContext context,
            ActionFabric.ActionObservation observation,
            CognitiveWorkerRuntime.Reflection proposed) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(proposed, "proposed");
        if (proposed.decision() != CognitiveWorkerRuntime.Decision.COMPLETE) return proposed;
        List<String> missing = new ArrayList<>();
        boolean latestResearchPassed = GeneralWebResearchAction.ACTION_REF.equals(observation.actionRef())
                && observation.success();
        if (requiresExternalResearch(context)
                && !latestResearchPassed
                && !successfulAction(context, GeneralWebResearchAction.ACTION_REF)) {
            missing.add("successful " + GeneralWebResearchAction.ACTION_REF);
        }
        if (requiresExternalResearch(context)
                && (latestResearchPassed || successfulAction(context, GeneralWebResearchAction.ACTION_REF))) {
            missing.addAll(researchCompletionQualityProblems(context, observation, proposed.summary()));
        }
        boolean latestMaterializationPassed = "workspace.repository.materialize".equals(observation.actionRef())
                && observation.success();
        if (requiresRepositoryMaterialization(context)
                && !latestMaterializationPassed
                && !materializationSatisfied(context)) {
            missing.add("successful workspace.repository.materialize");
        }
        boolean latestSourceMutation = observation.success()
                && ("workspace.shell.run".equals(observation.actionRef())
                || "workspace.process.run".equals(observation.actionRef())
                || (("workspace.file.write".equals(observation.actionRef())
                || "workspace.file.patch".equals(observation.actionRef()))
                && (!hasMarker(context, GeneralWorkspacePhasePlanner.PHASE_PRODUCE)
                || !isProjectManifestPath(observation.outputs().getOrDefault("path", "")))));
        boolean priorSourceMutation = hasMarker(context, GeneralWorkspacePhasePlanner.PHASE_PRODUCE)
                ? hasWorkspaceWorkProductMutation(context)
                : hasWorkspaceSourceMutation(context);
        boolean sourceWorkProductRequired = requiresWorkspaceSourceMutation(context)
                || (isFreshNewApplicationWork(context) && !phased(context));
        if (sourceWorkProductRequired
                && !latestSourceMutation
                && !priorSourceMutation) {
            missing.add(isFreshNewApplicationWork(context)
                    ? "workspace source/work-product for the new application (workspace.file.write/patch)"
                    : "workspace source/work-product mutation");
        }
        boolean latestManifestMutation = observation.success()
                && ("workspace.project.prepare".equals(observation.actionRef())
                || (("workspace.file.write".equals(observation.actionRef())
                || "workspace.file.patch".equals(observation.actionRef()))
                && isProjectManifestPath(observation.outputs().getOrDefault("path", ""))));
        if (hasMarker(context, GeneralWorkspacePhasePlanner.PHASE_PREPARE)
                && hasMarker(context, GeneralWorkspacePhasePlanner.REQUIRE_MANIFEST)
                && !latestManifestMutation
                && !hasProjectManifestMutation(context)) {
            missing.add("project/dependency manifest for governed verification");
        }
        boolean latestBuildPassed = "workspace.build.run".equals(observation.actionRef()) && observation.success();
        if (requiresGovernedBuild(context)
                && !latestBuildPassed
                && !successfulAction(context, "workspace.build.run")) {
            missing.add("successful workspace.build.run");
        }
        boolean latestTestPassed = "workspace.test.run".equals(observation.actionRef()) && observation.success();
        if (requiresGovernedTest(context)
                && !latestTestPassed
                && !governedTestSatisfied(context)) {
            missing.add("successful workspace.test.run after the latest source mutation");
        }
        boolean latestRemoteProposalPassed = "workspace.github.pr.publish".equals(observation.actionRef())
                && observation.success();
        boolean priorRemoteProposalPassed = successfulAction(context, "workspace.github.pr.publish");
        boolean committedWorkProductProvenByPublication = latestRemoteProposalPassed || priorRemoteProposalPassed;
        if (requiresGitAdd(context)
                && !successfulGitSubcommand(context, "add")
                && !committedWorkProductProvenByPublication) {
            missing.add("successful workspace.git.run add or committed-work proof from workspace.github.pr.publish");
        }
        if (requiresGitCommit(context)
                && !successfulGitSubcommand(context, "commit")
                && !committedWorkProductProvenByPublication) {
            missing.add("successful workspace.git.run commit or committed-work proof from workspace.github.pr.publish");
        }
        boolean latestGitStatusPassed = "workspace.git.status".equals(observation.actionRef()) && observation.success();
        if (requiresGitVerification(context)
                && !latestGitStatusPassed
                && !successfulAction(context, "workspace.git.status")) {
            missing.add("successful workspace.git.status verification");
        }
        if (requiresRemoteProposal(context)
                && !latestRemoteProposalPassed
                && !successfulAction(context, "workspace.github.pr.publish")) {
            missing.add("successful workspace.github.pr.publish");
        }
        boolean latestRuntimeVerificationPassed =
                "workspace.process.run".equals(observation.actionRef()) && observation.success();
        if (requiresRuntimeVerification(context)
                && !latestRuntimeVerificationPassed
                && !successfulAction(context, "workspace.process.run")) {
            missing.add("successful workspace.process.run runtime verification");
        }
        if (missing.isEmpty()) return proposed;
        return CognitiveWorkerRuntime.Reflection.continueWith(
                "completion rejected: Work still requires " + String.join(", ", missing));
    }

    public List<String> evidenceReferences() {
        return List.copyOf(evidence);
    }

    private CognitiveProviderResult completeObject(
            CognitiveWorkerRuntime.CognitiveContext context,
            String system,
            String user) {
        return completeObject(context, system, user, CognitiveOutputBudget.SELECTION);
    }

    private CognitiveProviderResult completeObject(
            CognitiveWorkerRuntime.CognitiveContext context,
            String system,
            String user,
            CognitiveOutputBudget outputBudget) {
        WorkerIntelligenceService.Response response = intelligence.reason(
                new WorkerIntelligenceService.Request(
                        context.workerId(),
                        "worker.cognition",
                        system,
                        user,
                        List.copyOf(evidence),
                        context.workerId(),
                        context.objectiveId(),
                        context.assignmentReference(),
                        context.workSpec().stepId(),
                        "",
                        outputBudget));
        Map<String, Object> parsed;
        try {
            parsed = parseObject(response.text());
        } catch (RuntimeException invalidResponse) {
            throw new IllegalStateException(
                    "invalid Intelligence cognitive JSON: " + invalidResponse.getMessage(), invalidResponse);
        }
        evidence.addAll(response.evidenceReferences());
        evidence.add("cognitive-intelligence-request:" + response.requestReference());
        return new CognitiveProviderResult(response.requestReference(), parsed);
    }

    /**
     * Deterministically classifies this cycle's output-token budget before any provider call: if the
     * catalog of actions actually offered this cycle includes one whose contract carries a
     * content-bearing input (a full file body, patch replacement text, or PR body), the model's JSON
     * response must itself be able to hold that generated content, so the larger CONTENT_GENERATION
     * ceiling applies. Plain action selection among non-content-bearing actions stays on the small
     * SELECTION ceiling. The classification is computed here, never guessed by the provider transport.
     */
    private static CognitiveOutputBudget outputBudgetFor(CognitiveWorkerRuntime.CognitiveContext context) {
        for (String action : context.availableActions()) {
            Map<String, Object> contract = ActionContractCatalog.promptContract(action);
            Object rawInputs = contract.getOrDefault("inputs", Map.of());
            if (rawInputs instanceof Map<?, ?> inputs && CONTENT_BEARING_INPUT_KEYS.stream()
                    .anyMatch(key -> inputs.containsKey(key))) {
                return CognitiveOutputBudget.CONTENT_GENERATION;
            }
        }
        return CognitiveOutputBudget.SELECTION;
    }

    private String contextPrompt(CognitiveWorkerRuntime.CognitiveContext context) {
        String rendered = renderContextPrompt(context);
        if (rendered.length() > MAX_CONTEXT_PROMPT_CHARS) {
            throw new IllegalStateException("worker-cognition-request-context-budget-exceeded:chars="
                    + rendered.length() + ":limit=" + MAX_CONTEXT_PROMPT_CHARS
                    + ":worker=" + context.workerId()
                    + ":assignment=" + context.assignmentReference()
                    + ":objective=" + context.objectiveId());
        }
        return rendered;
    }

    private String latestObservationPrompt(ActionFabric.ActionObservation observation) {
        return "\nLATEST_OBSERVATION=" + write(Map.of(
                "actionRef", observation.actionRef(),
                "success", observation.success(),
                "summary", observation.summary(),
                "outputs", observation.outputs(),
                "evidence", observation.evidenceReferences()));
    }

    /**
     * Characters (instructions + context) of the action-selection request this brain would send for
     * {@code context}, after its own history compaction. Lets a governing capability shape the context to the
     * budget before asking, instead of discovering the overflow as a failed cycle.
     */
    public int actionSelectionRequestChars(CognitiveWorkerRuntime.CognitiveContext context) {
        return ACTION_SELECTION_SYSTEM.length() + renderContextPrompt(providerActionSelectionContext(context)).length();
    }

    /** Characters (instructions + context) of the reflection request this brain would send for the observation. */
    public int reflectionRequestChars(CognitiveWorkerRuntime.CognitiveContext context,
                                      ActionFabric.ActionObservation observation) {
        return REFLECTION_SYSTEM.length() + renderContextPrompt(context).length()
                + latestObservationPrompt(observation).length();
    }

    /**
     * Bounded digest of one governance document for Work that must read documents too large to carry verbatim in
     * a Worker's cognition context. Every request stays within {@link #MAX_CONTEXT_PROMPT_CHARS} (instructions +
     * context): a document that does not fit one request is split at line boundaries, each part is digested, and
     * the part digests are merged (map-reduce) until one digest of at most {@code maxChars} remains. The model is
     * instructed to keep required items, gates, envelope, rules, gap codes and statuses verbatim and to add nothing
     * that is not in the text. A digest still over budget after one shortening request fails explicitly; it is never
     * silently truncated.
     */
    public String digestDocument(CognitiveWorkerRuntime.CognitiveContext context, String documentRef, String text,
                                 int maxChars) {
        Objects.requireNonNull(context, "context");
        if (documentRef == null || documentRef.isBlank()) throw new IllegalArgumentException("documentRef required");
        if (maxChars < 1) throw new IllegalArgumentException("maxChars must be positive");
        String document = text == null ? "" : text;
        List<String> parts = new ArrayList<>();
        List<String> chunks = digestChunks(documentRef, document, "part");
        for (int i = 0; i < chunks.size(); i++) {
            parts.add(digestCall(context, documentRef, "part", (i + 1) + "/" + chunks.size(), chunks.get(i),
                    Math.min(maxChars, Math.max(1, chunks.get(i).length()))));
        }
        while (parts.size() > 1 || parts.getFirst().length() > maxChars) {
            if (parts.size() == 1) {
                parts = List.of(digestCall(context, documentRef, "shorten", "1/1", parts.getFirst(), maxChars));
                break;
            }
            List<String> groups = digestChunks(documentRef, String.join("\n\n", parts), "merge");
            List<String> merged = new ArrayList<>();
            for (int i = 0; i < groups.size(); i++) {
                merged.add(digestCall(context, documentRef, "merge", (i + 1) + "/" + groups.size(), groups.get(i), maxChars));
            }
            if (merged.size() >= parts.size()) {
                throw new IllegalStateException("worker-document-digest-not-converging:document=" + documentRef);
            }
            parts = merged;
        }
        return parts.getFirst();
    }

    private static final String DIGEST_SYSTEM = """
            You condense one governance document, or one part of it, for a governed Metatron Worker that must plan from it.
            Keep verbatim every required item, gate, envelope or authority limit, rule, gap codes and identifiers, and status values.
            Do not add, infer, generalize or evaluate anything that is not in the text. Prefer terse lists over prose.
            mode=part: condense this part. mode=merge: merge these partial digests of the same document without losing any kept item.
            mode=shorten: shorten this digest without losing any kept item.
            The digest MUST be at most maxChars characters.
            Return ONLY JSON: {"digest":"..."}.
            """;

    private String digestCall(CognitiveWorkerRuntime.CognitiveContext context, String documentRef, String mode,
                              String part, String text, int maxChars) {
        String digest = requestDigest(context, documentRef, mode, part, text, maxChars);
        if (digest.length() > maxChars) {
            digest = requestDigest(context, documentRef, "shorten", part, digest, maxChars);
        }
        if (digest.length() > maxChars) {
            throw new IllegalStateException("worker-document-digest-over-budget:document=" + documentRef
                    + ":chars=" + digest.length() + ":limit=" + maxChars);
        }
        return digest;
    }

    private String requestDigest(CognitiveWorkerRuntime.CognitiveContext context, String documentRef, String mode,
                                 String part, String text, int maxChars) {
        String user = digestPrompt(documentRef, mode, part, text, maxChars);
        if (DIGEST_SYSTEM.length() + user.length() > MAX_CONTEXT_PROMPT_CHARS) {
            throw new IllegalStateException("worker-document-digest-request-over-budget:document=" + documentRef
                    + ":chars=" + (DIGEST_SYSTEM.length() + user.length()));
        }
        Object digest = completeObject(context, DIGEST_SYSTEM, user).parsed().get("digest");
        String value = digest == null ? "" : String.valueOf(digest).strip();
        if (value.isEmpty()) throw new IllegalStateException("worker-document-digest-empty:document=" + documentRef);
        return value;
    }

    private String digestPrompt(String documentRef, String mode, String part, String text, int maxChars) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("document", documentRef);
        payload.put("mode", mode);
        payload.put("part", part);
        payload.put("maxChars", maxChars);
        payload.put("text", text);
        return write(payload);
    }

    /** Splits text at line boundaries into pieces whose digest request fits the request budget. */
    private List<String> digestChunks(String documentRef, String text, String mode) {
        int budget = MAX_CONTEXT_PROMPT_CHARS - DIGEST_SYSTEM.length();
        if (digestPrompt(documentRef, mode, "1/1", text, 1).length() <= budget) return List.of(text);
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : text.split("(?<=\n)")) {
            for (String piece : splitOversizedLine(documentRef, mode, line, budget)) {
                if (current.length() > 0
                        && digestPrompt(documentRef, mode, "999/999", current + piece, 1).length() > budget) {
                    chunks.add(current.toString());
                    current.setLength(0);
                }
                current.append(piece);
            }
        }
        if (current.length() > 0) chunks.add(current.toString());
        return chunks;
    }

    private List<String> splitOversizedLine(String documentRef, String mode, String line, int budget) {
        if (digestPrompt(documentRef, mode, "999/999", line, 1).length() <= budget) return List.of(line);
        int half = line.length() / 2;
        List<String> pieces = new ArrayList<>(splitOversizedLine(documentRef, mode, line.substring(0, half), budget));
        pieces.addAll(splitOversizedLine(documentRef, mode, line.substring(half), budget));
        return pieces;
    }

    private String renderContextPrompt(CognitiveWorkerRuntime.CognitiveContext context) {
        List<String> catalog = context.availableActions();
        Map<String, List<String>> actionInputKeys = new LinkedHashMap<>();
        for (String action : catalog) {
            Map<String, Object> contract = ActionContractCatalog.promptContract(action);
            if (contract.isEmpty()) continue;
            Object rawInputs = contract.getOrDefault("inputs", Map.of());
            if (rawInputs instanceof Map<?, ?> inputs) {
                actionInputKeys.put(action, inputs.keySet().stream()
                        .map(String::valueOf).sorted().toList());
            }
        }
        List<Map<String, Object>> history = context.history().stream()
                .skip(Math.max(0, context.history().size() - 10L))
                .map(cycle -> Map.<String, Object>of(
                        "cycle", cycle.number(),
                        "actionRef", cycle.thought().actionRef(),
                        "inputs", boundedPromptMap(cycle.thought().inputs()),
                        "observationSuccess", cycle.observation().success(),
                        "observationSummary", boundedPromptText(
                                cycle.observation().summary(), MAX_HISTORY_SUMMARY_CHARS),
                        "observationOutputs", boundedPromptMap(cycle.observation().outputs()),
                        "reflection", cycle.reflection().decision().name(),
                        "reflectionSummary", boundedPromptText(
                                cycle.reflection().summary(), MAX_HISTORY_SUMMARY_CHARS)))
                .toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("work", Map.of(
                "stepId", context.workSpec().stepId(),
                "objective", context.workSpec().objective(),
                "target", context.workSpec().target(),
                "requiredCapability", context.workSpec().requiredCapability(),
                "consequence", context.workSpec().consequence().name(),
                "acceptanceCriteria", context.workSpec().acceptanceCriteria(),
                "evidenceRequirements", context.workSpec().evidenceRequirements()));
        payload.put("availableActions", catalog);
        payload.put("actionInputKeys", actionInputKeys);
        Map<String, String> cognitionMemory = new LinkedHashMap<>(context.memory());
        // Duplicates already embedded in workerConstitution; retain them durably outside the model prompt.
        cognitionMemory.remove("workerConstitutionSnapshotRef");
        cognitionMemory.remove("workerConstitutionMaterializedAt");
        payload.put("memory", Map.copyOf(cognitionMemory));
        payload.put("recentCycles", history);
        String rendered = write(payload);
        if (rendered.length() > MAX_CONTEXT_PROMPT_CHARS && !context.history().isEmpty()) {
            CognitiveWorkerRuntime.Cycle latest = context.history().getLast();
            payload.put("recentCycles", List.of(Map.<String, Object>of(
                    "cycle", latest.number(),
                    "actionRef", latest.thought().actionRef(),
                    "observationSuccess", latest.observation().success(),
                    "observationSummary", boundedPromptText(latest.observation().summary(), 160),
                    "reflection", latest.reflection().decision().name(),
                    "reflectionSummary", boundedPromptText(latest.reflection().summary(), 160),
                    "_contextCompaction", "LATEST_CYCLE_MINIMAL")));
            rendered = write(payload);
        }
        if (rendered.length() > MAX_CONTEXT_PROMPT_CHARS && !context.history().isEmpty()) {
            CognitiveWorkerRuntime.Cycle latest = context.history().getLast();
            payload.put("recentCycles", List.of(Map.<String, Object>of(
                    "cycle", latest.number(),
                    "actionRef", latest.thought().actionRef(),
                    "observationSuccess", latest.observation().success(),
                    "reflection", latest.reflection().decision().name(),
                    "_contextCompaction", "LATEST_CYCLE_IDENTITY_ONLY")));
            rendered = write(payload);
        }
        return rendered;
    }

    private static Map<String, String> boundedPromptMap(Map<String, String> values) {
        if (values == null || values.isEmpty()) return Map.of();
        Map<String, String> bounded = new LinkedHashMap<>();
        values.entrySet().stream().limit(12).forEach(entry -> bounded.put(
                entry.getKey(), boundedPromptText(entry.getValue(), MAX_HISTORY_OUTPUT_VALUE_CHARS)));
        if (values.size() > bounded.size()) {
            bounded.put("_contextCompaction", "EXPLICITLY_OMITTED_ENTRIES=" + (values.size() - bounded.size()));
        }
        return Map.copyOf(bounded);
    }

    private static String boundedPromptText(String value, int maxChars) {
        String normalized = value == null ? "" : value;
        if (normalized.length() <= maxChars) return normalized;
        return normalized.substring(0, maxChars)
                + "...[EXPLICITLY_COMPACTED original_chars=" + normalized.length() + "]";
    }

    private Map<String, Object> parseObject(String raw) {
        String value = raw == null ? "" : raw.trim();
        List<String> candidates = topLevelJsonObjectCandidates(value);
        if (candidates.isEmpty()) {
            throw new IllegalStateException(
                    "cognitive provider returned no JSON object (raw_preview=" + diagnosticPreview(value) + ")");
        }
        Exception lastFailure = null;
        for (int i = candidates.size() - 1; i >= 0; i--) {
            try {
                return json.readValue(candidates.get(i), new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                lastFailure = e;
            }
        }
        throw new IllegalStateException(
                "invalid cognitive provider JSON (candidates=" + candidates.size()
                        + ", cause=" + (lastFailure == null ? "unknown" : lastFailure.getMessage())
                        + ", raw_preview=" + diagnosticPreview(value) + ")",
                lastFailure);
    }

    /**
     * Bounded, whitespace-collapsed preview of a raw provider response for diagnosability.
     * Kept short and reused only in fail-closed exception messages, never in evidence sent
     * back to a provider or a Human, since JSON-parse failures otherwise leave zero trace of
     * the actual malformed text anywhere in production logs or evidence.
     */
    private static String diagnosticPreview(String value) {
        String collapsed = value.replaceAll("\\s+", " ").trim();
        int max = 400;
        return collapsed.length() <= max ? collapsed : collapsed.substring(0, max) + "...[truncated]";
    }

    /**
     * Scans for every top-level, brace-balanced {...} substring, respecting JSON string
     * literals so that braces quoted inside prose (e.g. source code shown during a code
     * review) never desynchronize the balance count. The model's real answer is typically
     * the last such candidate, since providers often preface it with reasoning or quoted
     * examples that legitimately contain their own brace characters.
     */
    private static List<String> topLevelJsonObjectCandidates(String value) {
        List<String> candidates = new ArrayList<>();
        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                if (depth > 0) {
                    depth--;
                    if (depth == 0 && start >= 0) {
                        candidates.add(value.substring(start, i + 1));
                        start = -1;
                    }
                }
            }
        }
        return candidates;
    }

    private Map<String, String> stringMap(Object raw) {
        if (raw == null) return Map.of();
        if (!(raw instanceof Map<?, ?> map)) throw new IllegalStateException("cognitive inputs must be an object");
        Map<String, String> out = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            String k = String.valueOf(key);
            if (value instanceof String text) out.put(k, text);
            else out.put(k, write(value));
        });
        return Map.copyOf(out);
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("cannot serialize cognitive context", e); }
    }

    private static String text(Object value, String field) {
        String out = value == null ? "" : String.valueOf(value).trim();
        if (out.isBlank()) throw new IllegalStateException("cognitive response missing " + field);
        return out;
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? "unknown" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String failureSummary(RuntimeException failure) {
        String value = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        String clean = value.replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() <= 600 ? clean : clean.substring(0, 600);
    }

    private record CognitiveProviderResult(String requestReference, Map<String, Object> parsed) {}

    private record ExactTextReplacement(String path, String oldText, String newText) {}

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
        return value.trim();
    }
}
