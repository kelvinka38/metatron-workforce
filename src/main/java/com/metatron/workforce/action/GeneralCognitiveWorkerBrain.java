package com.metatron.workforce.action;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmProviderRouter;
import com.metatron.workforce.interaction.llm.LlmRequest;
import com.metatron.workforce.interaction.llm.LlmResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Provider-backed general Cognitive Worker brain. Action authority remains entirely in ActionFabric. */
public final class GeneralCognitiveWorkerBrain implements CognitiveWorkerRuntime.Brain {
    private static final Pattern OWNER_REPOSITORY = Pattern.compile("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$");
    private static final Pattern EXACT_GIT_SHA = Pattern.compile("(?<![0-9a-fA-F])[0-9a-fA-F]{40}(?![0-9a-fA-F])");
    private static final Pattern RESEARCH_TOP_N = Pattern.compile("(?i)\\b(?:top\\s*|exactly\\s+)(\\d{1,2})\\b");
    private static final Pattern RESEARCH_URL = Pattern.compile("https?://[^\\s)\\]}>;,]+");
    private static final Pattern RESEARCH_ITEM = Pattern.compile("(?m)^\\s*(?:\\d+[.)]|[-*]\\s*\\d+[.)])\\s+");
    private static final Pattern RESEARCH_DECISION = Pattern.compile("(?i)\\b(?:KEEP|TEST|CHANGE|REJECT)\\b");
    private static final ObjectMapper ACTION_INPUT_JSON = new ObjectMapper();
    private static final Map<String, Map<String, Object>> ACTION_CONTRACTS = Map.ofEntries(
            Map.entry(GeneralWebResearchAction.ACTION_REF, Map.of(
                    "inputs", Map.of("query", "required external research/search requirement"),
                    "purpose", "retrieve current external evidence through the governed web search ToolFabric; read-only and source-attributed")),
            Map.entry("workspace.repository.materialize", Map.of(
                    "inputs", Map.of("repository", "required owner/name", "ref", "optional branch/tag/SHA; default main"),
                    "purpose", "materialize an immutable private/public GitHub repository snapshot into this Objective workspace and create a local Git baseline; safe same-provenance retries reuse the existing materialization")),
            Map.entry("workspace.file.read", Map.of(
                    "inputs", Map.of("path", "required workspace-relative path"),
                    "purpose", "read one UTF-8 workspace file")),
            Map.entry("workspace.file.list", Map.of(
                    "inputs", Map.of("path", "optional workspace-relative directory; empty means root"),
                    "purpose", "list bounded workspace paths")),
            Map.entry("workspace.file.write", Map.of(
                    "inputs", Map.of("path", "required workspace-relative path", "content", "required file content; empty allowed"),
                    "purpose", "create or replace one workspace file")),
            Map.entry("workspace.process.run", Map.of(
                    "inputs", Map.of("executable", "required executable from runtime allowlist", "argsJson", "JSON string array of arguments"),
                    "purpose", "run one allowlisted process in the isolated Objective sandbox")),
            Map.entry("workspace.shell.run", Map.of(
                    "inputs", Map.of("command", "required constrained command; no pipes, redirects, chaining or substitution"),
                    "purpose", "run one constrained allowlisted command in the isolated Objective sandbox")),
            Map.entry("workspace.git.status", Map.of(
                    "inputs", Map.of(), "purpose", "read-only inspection of local Git status, immutable HEAD SHA and bounded recent commit log")),
            Map.entry("workspace.git.diff", Map.of(
                    "inputs", Map.of(), "purpose", "inspect uncommitted local Git diff")),
            Map.entry("workspace.git.run", Map.of(
                    "inputs", Map.of("argsJson", "required JSON string array of git arguments"),
                    "purpose", "run a local Git operation; no remote credential is exposed to the sandbox")),
            Map.entry("workspace.build.run", Map.of(
                    "inputs", Map.of("tasksJson", "optional JSON string array of build tasks"),
                    "purpose", "detect Gradle/Maven project and run its build in the isolated sandbox")),
            Map.entry("workspace.test.run", Map.of(
                    "inputs", Map.of("tasksJson", "optional JSON string array of test tasks"),
                    "purpose", "detect Gradle/Maven project and run tests in the isolated sandbox"))
    );

    private final LlmProviderRouter router;
    private final LlmProvider provider;
    private final String model;
    private final ObjectMapper json;
    private final List<String> evidence = new ArrayList<>();

    public GeneralCognitiveWorkerBrain(LlmProviderRouter router, LlmProvider provider, String model, ObjectMapper json) {
        this.router = Objects.requireNonNull(router, "router");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.model = require(model, "model");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public CognitiveWorkerRuntime.Thought think(CognitiveWorkerRuntime.CognitiveContext context) {
        CognitiveWorkerRuntime.Thought researchPrecondition = researchSearchPrecondition(context);
        if (researchPrecondition != null) return researchPrecondition;
        CognitiveWorkerRuntime.Thought requiredPrecondition = repositoryMaterializationPrecondition(context);
        if (requiredPrecondition != null) return requiredPrecondition;
        CognitiveWorkerRuntime.Thought requiredFileWrite = governedExactShaFileWritePrecondition(context);
        if (requiredFileWrite != null) return requiredFileWrite;
        CognitiveWorkerRuntime.Thought requiredTest = governedTestPrecondition(context);
        if (requiredTest != null) return requiredTest;
        CognitiveWorkerRuntime.Thought requiredGit = governedGitPrecondition(context);
        if (requiredGit != null) return requiredGit;
        CognitiveWorkerRuntime.Thought gitInspection = readOnlyGitInspectionPrecondition(context);
        if (gitInspection != null) return gitInspection;

        String system = """
                You are the action-selection brain for a governed Metatron Cognitive Worker.
                You have no authority to execute outside the supplied action catalog.
                Select exactly one next action that advances the actual Work using current observations.
                Do not invent action names or input keys. Follow the supplied actionContracts exactly.
                If the Work targets a repository and the workspace has not yet been materialized, use workspace.repository.materialize before any Git, build, test or repository-file action.
                Repository materialization creates a local baseline commit. The authoritative source snapshot identity is sourceCommitSha from materialization provenance; localBaselineCommitSha and later local HEAD identify the mutable Objective workspace and need not equal sourceCommitSha.
                Do not claim completion in this response.
                Inputs must be concrete strings. For list arguments use a JSON array encoded as a string in argsJson/tasksJson.
                Return ONLY JSON: {"actionRef":"...","inputs":{"key":"value"},"rationale":"short operational reason"}.
                """;
        LlmResponse response = complete(system, contextPrompt(context));
        Map<String, Object> parsed = parseObject(response.text());
        String actionRef = text(parsed.get("actionRef"), "actionRef");
        String rationale = text(parsed.get("rationale"), "rationale");
        Map<String, String> inputs = stringMap(parsed.get("inputs"));
        return new CognitiveWorkerRuntime.Thought(actionRef, inputs, rationale);
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
        return new CognitiveWorkerRuntime.Thought(
                "workspace.repository.materialize",
                Map.copyOf(inputs),
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


    static CognitiveWorkerRuntime.Thought governedExactShaFileWritePrecondition(
            CognitiveWorkerRuntime.CognitiveContext context) {
        Objects.requireNonNull(context, "context");
        if (!context.availableActions().contains("workspace.file.write")) return null;
        if (!requiresExactShaFileWrite(context)) return null;
        if (successfulAction(context, "workspace.file.write")) return null;

        String path = governedStagePath(context.workSpec().target());
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
        String path = governedStagePath(context.workSpec().target());
        if (path.isBlank()) return false;
        String text = workText(context).toLowerCase(java.util.Locale.ROOT);
        return !exactRef(context).isBlank()
                && (text.contains("create or replace only") || text.contains("write only"))
                && text.contains("exact utf-8 content:")
                && text.contains("source_sha=");
    }

    static CognitiveWorkerRuntime.Thought governedTestPrecondition(
            CognitiveWorkerRuntime.CognitiveContext context) {
        Objects.requireNonNull(context, "context");
        if (!context.availableActions().contains("workspace.test.run")) return null;
        if (!requiresGovernedTest(context)) return null;
        if (successfulAction(context, "workspace.test.run")) return null;
        if (requiresRepositoryMaterialization(context)
                && context.availableActions().contains("workspace.repository.materialize")
                && !successfulAction(context, "workspace.repository.materialize")) return null;
        return new CognitiveWorkerRuntime.Thought(
                "workspace.test.run", Map.of(),
                "Work explicitly requires the governed repository test suite to pass before completion");
    }

    static CognitiveWorkerRuntime.Thought governedGitPrecondition(
            CognitiveWorkerRuntime.CognitiveContext context) {
        Objects.requireNonNull(context, "context");
        if (!context.availableActions().contains("workspace.git.run")) return null;
        if (requiresGitAdd(context) && !successfulGitSubcommand(context, "add")) {
            String path = governedStagePath(context.workSpec().target());
            if (!path.isBlank()) {
                return new CognitiveWorkerRuntime.Thought(
                        "workspace.git.run",
                        Map.of("argsJson", writeActionArgs(List.of("add", path))),
                        "Work explicitly requires staging the governed target before local commit");
            }
        }
        if (requiresGitCommit(context)
                && (!requiresGitAdd(context) || successfulGitSubcommand(context, "add"))
                && !successfulGitSubcommand(context, "commit")) {
            return new CognitiveWorkerRuntime.Thought(
                    "workspace.git.run",
                    Map.of("argsJson", writeActionArgs(List.of(
                            "commit", "-m", "Complete governed Objective work step"))),
                    "Work explicitly requires one immutable local Git commit");
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

    private static boolean requiresRepositoryMaterialization(CognitiveWorkerRuntime.CognitiveContext context) {
        if (context.workSpec().consequence()
                != com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec.Consequence.READ_ONLY) {
            return false;
        }
        String text = workText(context).toLowerCase(java.util.Locale.ROOT);
        return text.contains("materializ")
                || text.contains("snapshot")
                || text.contains("checkout")
                || text.contains("source tree")
                || text.contains("git rev-parse")
                || EXACT_GIT_SHA.matcher(text).find();
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

    private static boolean requiresGovernedTest(CognitiveWorkerRuntime.CognitiveContext context) {
        String text = workText(context).toLowerCase(java.util.Locale.ROOT);
        return text.contains("test suite")
                || text.contains("run test")
                || text.contains("execute test")
                || text.contains("tests pass")
                || text.contains("test action")
                || text.contains("workspace.test.run");
    }

    private static boolean successfulAction(CognitiveWorkerRuntime.CognitiveContext context, String actionRef) {
        return context.history().stream().anyMatch(cycle ->
                actionRef.equals(cycle.thought().actionRef()) && cycle.observation().success());
    }

    private static boolean requiresGitAdd(CognitiveWorkerRuntime.CognitiveContext context) {
        if (context.workSpec().consequence()
                != com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec.Consequence.MUTATING) return false;
        String text = workText(context).toLowerCase(java.util.Locale.ROOT);
        return text.contains("stage ") || text.contains("staged ") || text.contains("git add");
    }

    private static boolean requiresGitCommit(CognitiveWorkerRuntime.CognitiveContext context) {
        if (context.workSpec().consequence()
                != com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec.Consequence.MUTATING) return false;
        String text = workText(context).toLowerCase(java.util.Locale.ROOT);
        return text.contains("local git commit")
                || text.contains("local commit")
                || text.contains("create one commit")
                || text.contains("create a commit")
                || text.contains("commit exists")
                || text.contains("commit the ")
                || text.contains("commit message");
    }

    private static boolean requiresGitVerification(CognitiveWorkerRuntime.CognitiveContext context) {
        if (!requiresGitCommit(context)) return false;
        String text = workText(context).toLowerCase(java.util.Locale.ROOT);
        return text.contains("git show")
                || text.contains("verify")
                || text.contains("commit exists")
                || text.contains("exactly the change");
    }

    private static String governedStagePath(String target) {
        String path = target == null ? "" : target.trim();
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
        String repository = target == null ? "" : target.trim();
        int at = repository.indexOf('@');
        if (at > 0) repository = repository.substring(0, at);
        return OWNER_REPOSITORY.matcher(repository).matches() ? repository : "";
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
        if (!workspaceRef.startsWith("objective-workspace:") || materializedFiles < 1) {
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
    public CognitiveWorkerRuntime.Reflection reflect(CognitiveWorkerRuntime.CognitiveContext context,
                                                      ActionFabric.ActionObservation observation) {
        CognitiveWorkerRuntime.Reflection canonicalMaterialization =
                exactSourceMaterializationReflection(context, observation);
        if (canonicalMaterialization != null) return canonicalMaterialization;
        CognitiveWorkerRuntime.Reflection requiredAction = governedRequiredActionReflection(context, observation);
        if (requiredAction != null) {
            return enforceRequiredActionCompletion(context, observation, requiredAction);
        }

        String system = """
                You are the reflection brain for a governed Metatron Cognitive Worker.
                Decide from the actual Work, acceptance criteria, evidence requirements and observed action result.
                COMPLETE only when every acceptance requirement can be supported by actual observations already obtained.
                CONTINUE if another governed action can advance or verify the Work.
                FAILED only when the observed state makes bounded recovery impossible.
                Never treat a successful intermediate action as completion of unrelated acceptance criteria.
                Repository source identity is proven by workspace.repository.materialize output sourceCommitSha. localBaselineCommitSha and workspace.git.status headSha are local Objective-workspace identities and may intentionally differ from sourceCommitSha.
                For external research/synthesis Work, a COMPLETE summary is the durable Work output, not a status sentence. It MUST contain the requested substantive deliverable, preserve source URLs or canonical identifiers from observations, distinguish evidence from inference, and explicitly state uncertainty.
                Never claim that N items were found unless at least N distinct attributable source URLs were actually observed.
                When the Work asks for a Top-N shortlist, the COMPLETE summary MUST enumerate items 1..N. Each item must include title, issuer/authors, publication/update date when observed, Source: <observed URL>, What is new, Why it matters, and Decision: KEEP|TEST|CHANGE|REJECT. End with the highest-potential model experiment and remaining uncertainty.
                If there are not enough qualified sources yet, CONTINUE with a narrower governed search. Do not substitute generic encyclopedias, promotional pages, tourism pages, or unrelated sources for missing evidence.
                Return ONLY JSON: {"decision":"CONTINUE|COMPLETE|FAILED","summary":"evidence-based result or next-step reason"}.
                """;
        String user = contextPrompt(context) + "\nLATEST_OBSERVATION=" + write(Map.of(
                "actionRef", observation.actionRef(),
                "success", observation.success(),
                "summary", observation.summary(),
                "outputs", observation.outputs(),
                "evidence", observation.evidenceReferences()));
        LlmResponse response = complete(system, user);
        Map<String, Object> parsed = parseObject(response.text());
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

    static CognitiveWorkerRuntime.Reflection governedRequiredActionReflection(
            CognitiveWorkerRuntime.CognitiveContext context,
            ActionFabric.ActionObservation observation) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(observation, "observation");
        if (!observation.success()) return null;
        if (requiresExactShaFileWrite(context) && "workspace.file.write".equals(observation.actionRef())) {
            return CognitiveWorkerRuntime.Reflection.complete(
                    "Governed workspace file write succeeded with the explicit exact-SHA proof content");
        }
        if (requiresGovernedTest(context) && "workspace.test.run".equals(observation.actionRef())) {
            return CognitiveWorkerRuntime.Reflection.complete(
                    "Governed test action succeeded; all explicit action requirements are now evaluated");
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
        boolean latestTestPassed = "workspace.test.run".equals(observation.actionRef()) && observation.success();
        if (requiresGovernedTest(context)
                && !latestTestPassed
                && !successfulAction(context, "workspace.test.run")) {
            missing.add("successful workspace.test.run");
        }
        if (requiresGitAdd(context)
                && !successfulGitSubcommand(context, "add")) {
            missing.add("successful workspace.git.run add");
        }
        if (requiresGitCommit(context)
                && !successfulGitSubcommand(context, "commit")) {
            missing.add("successful workspace.git.run commit");
        }
        boolean latestGitStatusPassed = "workspace.git.status".equals(observation.actionRef()) && observation.success();
        if (requiresGitVerification(context)
                && !latestGitStatusPassed
                && !successfulAction(context, "workspace.git.status")) {
            missing.add("successful workspace.git.status verification");
        }
        if (missing.isEmpty()) return proposed;
        return CognitiveWorkerRuntime.Reflection.continueWith(
                "completion rejected: Work still requires " + String.join(", ", missing));
    }

    public List<String> evidenceReferences() {
        return List.copyOf(evidence);
    }

    private LlmResponse complete(String system, String user) {
        LlmResponse response = router.complete(new LlmRequest(provider, model, system, user));
        evidence.add("cognitive-provider:" + response.provider()
                + ":model=" + response.model()
                + ":request=" + clean(response.providerRequestReference()));
        return response;
    }

    private String contextPrompt(CognitiveWorkerRuntime.CognitiveContext context) {
        List<String> catalog = context.availableActions();
        Map<String, Map<String, Object>> contracts = new LinkedHashMap<>();
        for (String action : catalog) {
            Map<String, Object> contract = ACTION_CONTRACTS.get(action);
            if (contract != null) contracts.put(action, contract);
        }
        List<Map<String, Object>> history = context.history().stream()
                .skip(Math.max(0, context.history().size() - 10L))
                .map(cycle -> Map.<String, Object>of(
                        "cycle", cycle.number(),
                        "actionRef", cycle.thought().actionRef(),
                        "inputs", cycle.thought().inputs(),
                        "observationSuccess", cycle.observation().success(),
                        "observationSummary", cycle.observation().summary(),
                        "observationOutputs", cycle.observation().outputs(),
                        "reflection", cycle.reflection().decision().name(),
                        "reflectionSummary", cycle.reflection().summary()))
                .toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("objectiveId", context.objectiveId());
        payload.put("workerId", context.workerId());
        payload.put("assignmentReference", context.assignmentReference());
        payload.put("work", Map.of(
                "stepId", context.workSpec().stepId(),
                "objective", context.workSpec().objective(),
                "target", context.workSpec().target(),
                "requiredCapability", context.workSpec().requiredCapability(),
                "consequence", context.workSpec().consequence().name(),
                "acceptanceCriteria", context.workSpec().acceptanceCriteria(),
                "evidenceRequirements", context.workSpec().evidenceRequirements()));
        payload.put("availableActions", catalog);
        payload.put("actionContracts", contracts);
        payload.put("memory", context.memory());
        payload.put("recentCycles", history);
        return write(payload);
    }

    private Map<String, Object> parseObject(String raw) {
        String value = raw == null ? "" : raw.trim();
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalStateException("cognitive provider returned no JSON object");
        try {
            return json.readValue(value.substring(start, end + 1), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("invalid cognitive provider JSON", e);
        }
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

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
        return value.trim();
    }
}
