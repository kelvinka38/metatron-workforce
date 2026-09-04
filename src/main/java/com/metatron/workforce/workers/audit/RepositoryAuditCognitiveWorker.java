package com.metatron.workforce.workers.audit;

import com.metatron.workforce.action.ActionFabric;
import com.metatron.workforce.action.ActionJournal;
import com.metatron.workforce.action.CognitiveWorkerRuntime;
import com.metatron.workforce.gateway.GatewayEgressClient;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.workers.WorkerResult;

import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only repository auditor implemented as a bounded Cognitive Worker.
 * Every external GitHub read is a separately governed Action Fabric invocation.
 */
final class RepositoryAuditCognitiveWorker {
    static final String ACTION_METADATA = "github.repository.metadata.read";
    static final String ACTION_HEAD = "github.repository.head.read";
    static final String ACTION_TREE = "github.repository.tree.read";
    static final String ACTION_CONTENT = "github.repository.content.inspect";

    private static final Pattern DEFAULT_BRANCH = Pattern.compile("\\\"default_branch\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern SHA = Pattern.compile("\\\"sha\\\"\\s*:\\s*\\\"([0-9a-f]{40})\\\"");
    private static final Pattern TREE_PATH = Pattern.compile("\\\"path\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final String PATH_SEPARATOR = "\u001f";
    /**
     * Repository audits are representative, evidence-backed inspections rather than full mirrors.
     * Keep the external GitHub call budget low enough that a four-repository parallel Objective does
     * not exhaust one shared credential or amplify a transient provider limit through whole-audit retries.
     */
    private static final int MAX_FILES = 24;
    private static final int MAX_BYTES = 2_000_000;

    private final GatewayEgressClient egress;
    private final boolean authenticated;
    private final String authorizationReference;
    private final ActionJournal journal;

    RepositoryAuditCognitiveWorker(String authorizationReference) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                "https://api.github.com", env("GITHUB_TOKEN"), authorizationReference,
                ActionJournal.runtimeEvidenceJournal());
    }

    RepositoryAuditCognitiveWorker(HttpClient http, String apiBase, String githubToken,
                                   String authorizationReference, ActionJournal journal) {
        this.authorizationReference = require(authorizationReference, "authorizationReference");
        String token = githubToken == null ? "" : githubToken.trim();
        this.egress = new GatewayEgressClient(http, apiBase, token, this.authorizationReference);
        this.authenticated = !token.isBlank();
        this.journal = journal == null ? ActionJournal.noop() : journal;
    }

    WorkerResult execute(String objectiveId, String workId, String assignmentReference,
                         String repository, ExecutionWorkSpec workSpec) {
        Instant completedAt = Instant.now();
        try {
            String target = requireRepository(repository);
            ActionFabric fabric = new ActionFabric(List.of(
                    metadataAction(target), headAction(target), treeAction(target), contentAction(target)));
            CognitiveWorkerRuntime runtime = new CognitiveWorkerRuntime(fabric, journal, MAX_FILES + 8);
            CognitiveWorkerRuntime.Outcome outcome = runtime.execute(
                    RepositoryAuditExecutionService.WORKER_ID,
                    require(assignmentReference, "assignmentReference"),
                    authorizationReference,
                    require(objectiveId, "objectiveId"),
                    workSpec,
                    workId,
                    new AuditBrain());

            AuditStats stats = summarize(outcome);
            if (!outcome.success() || stats.contentFilesRead == 0) {
                return failed(workId, target, "cognitive audit did not establish readable repository content: "
                        + outcome.summary(), completedAt);
            }
            String findings = findings(stats);
            String evidence = "Repository Audit Report\n"
                    + "task=" + workId + "\n"
                    + "objective=" + workSpec.objective() + "\n"
                    + "source=gateway-egress/github-api\n"
                    + "executionModel=cognitive-action-fabric\n"
                    + "authenticated=" + authenticated + "\n"
                    + "repository=" + target + "\n"
                    + "defaultBranch=" + outcome.memory().getOrDefault("defaultBranch", "") + "\n"
                    + "commitSha=" + outcome.memory().getOrDefault("commitSha", "") + "\n"
                    + "repositoryFilesObserved=" + stats.repositoryFilesObserved + "\n"
                    + "contentFilesRead=" + stats.contentFilesRead + "\n"
                    + "contentFilesSkipped=" + stats.unreadableContentFiles + "\n"
                    + "contentBytesRead=" + stats.contentBytesRead + "\n"
                    + "sotSignals=" + stats.sotSignals + "\n"
                    + "documentFilesRead=" + stats.documentFilesRead + "\n"
                    + "sourceFilesRead=" + stats.sourceFilesRead + "\n"
                    + "testFilesRead=" + stats.testFilesRead + "\n"
                    + "gatewayEgressCrossings=" + stats.gatewayCrossings + "\n"
                    + "cognitiveActionCount=" + outcome.cycles().size() + "\n"
                    + "findings=" + findings + "\n"
                    + "observedPaths=" + String.join(",", stats.observedPaths) + "\n"
                    + "skippedPaths=" + String.join(",", stats.skippedPaths) + "\n"
                    + "observedAt=" + outcome.completedAt() + "\n"
                    + "verdict=PASS\n";
            return new WorkerResult("RepositoryAuditWorker", "PASS", evidence, outcome.completedAt());
        } catch (Exception failure) {
            return failed(workId, repository, failure.getClass().getSimpleName() + ": "
                    + String.valueOf(failure.getMessage()), completedAt);
        }
    }

    private ActionFabric.Action metadataAction(String repository) {
        return new ReadAction(ACTION_METADATA) {
            @Override protected ActionFabric.ActionObservation perform(ActionFabric.ActionRequest request) throws Exception {
                GatewayEgressClient.EgressResponse response = get("/repos/" + repository, "application/vnd.github+json");
                requireHttp(response, 200, "repository metadata");
                String branch = capture(DEFAULT_BRANCH, response.body());
                if (branch == null) throw new IllegalStateException("GitHub response missing default_branch");
                return success(actionRef(), "repository metadata observed",
                        Map.of("defaultBranch", branch), response);
            }
        };
    }

    private ActionFabric.Action headAction(String repository) {
        return new ReadAction(ACTION_HEAD) {
            @Override protected ActionFabric.ActionObservation perform(ActionFabric.ActionRequest request) throws Exception {
                String branch = requiredInput(request, "defaultBranch");
                GatewayEgressClient.EgressResponse response = get("/repos/" + repository + "/commits/" + encode(branch),
                        "application/vnd.github+json");
                requireHttp(response, 200, "default branch commit");
                String commitSha = capture(SHA, response.body());
                if (commitSha == null) throw new IllegalStateException("GitHub response missing commit SHA");
                return success(actionRef(), "repository head observed",
                        Map.of("commitSha", commitSha), response);
            }
        };
    }

    private ActionFabric.Action treeAction(String repository) {
        return new ReadAction(ACTION_TREE) {
            @Override protected ActionFabric.ActionObservation perform(ActionFabric.ActionRequest request) throws Exception {
                String commitSha = requiredInput(request, "commitSha");
                GatewayEgressClient.EgressResponse response = get("/repos/" + repository + "/git/trees/"
                        + commitSha + "?recursive=1", "application/vnd.github+json");
                requireHttp(response, 200, "repository tree");
                List<String> allPaths = paths(response.body());
                List<String> selected = select(allPaths);
                if (selected.isEmpty()) throw new IllegalStateException("repository tree contains no auditable files");
                if (selected.size() > MAX_FILES) selected = selected.subList(0, MAX_FILES);
                return success(actionRef(), "repository tree observed and bounded audit set selected",
                        Map.of("selectedPaths", String.join(PATH_SEPARATOR, selected),
                                "repositoryFilesObserved", Integer.toString(allPaths.size())), response);
            }
        };
    }

    private ActionFabric.Action contentAction(String repository) {
        return new ReadAction(ACTION_CONTENT) {
            @Override protected ActionFabric.ActionObservation perform(ActionFabric.ActionRequest request) throws Exception {
                String commitSha = requiredInput(request, "commitSha");
                String path = requiredInput(request, "path");
                if (!auditable(path.toLowerCase())) throw new SecurityException("non-auditable path selected");
                GatewayEgressClient.EgressResponse response = get("/repos/" + repository + "/contents/"
                        + encodePath(path) + "?ref=" + commitSha, "application/vnd.github.raw+json");
                if (response.statusCode() != 200) {
                    return success(actionRef(), "repository content unavailable; recorded and skipped: "
                                    + path + " HTTP " + response.statusCode(),
                            unreadableOutputs(path, response.statusCode()), response);
                }
                String body = response.body();
                int bytes = body.getBytes(StandardCharsets.UTF_8).length;
                if (bytes > MAX_BYTES) {
                    return success(actionRef(), "repository content exceeds bounded audit byte budget; recorded and skipped: " + path,
                            unreadableOutputs(path, response.statusCode()), response);
                }
                String lower = path.toLowerCase();
                Map<String, String> outputs = Map.of(
                        "path", path,
                        "readable", "true",
                        "httpStatus", Integer.toString(response.statusCode()),
                        "bytes", Integer.toString(bytes),
                        "sot", bool(lower.contains("sot") || body.contains("SOURCE OF TRUTH") || body.contains("Source of Truth")),
                        "docs", bool(lower.endsWith(".md") || lower.endsWith(".txt")),
                        "source", bool(lower.matches(".*\\.(java|kt|py|js|ts|go|rs)$")),
                        "tests", bool(lower.contains("test") || lower.contains("spec")),
                        "todo", Integer.toString(count(body, "TODO") + count(body, "FIXME")),
                        "conflict", Integer.toString(count(body, "<<<<<<< ") + count(body, ">>>>>>> ")));
                return success(actionRef(), "repository content inspected: " + path, outputs, response);
            }
        };
    }

    private abstract class ReadAction implements ActionFabric.Action {
        private final String ref;
        private ReadAction(String ref) { this.ref = ref; }
        @Override public final String actionRef() { return ref; }
        @Override public final ActionFabric.Consequence consequence() { return ActionFabric.Consequence.READ_ONLY; }
        @Override public final Set<String> allowedWorkers() { return Set.of(RepositoryAuditExecutionService.WORKER_ID); }
        @Override public final Set<String> acceptedAuthorizations() { return Set.of(authorizationReference); }
        @Override public final ActionFabric.ActionObservation invoke(ActionFabric.ActionRequest request) {
            try { return perform(request); }
            catch (Exception failure) {
                throw new IllegalStateException(actionRef() + " failed: " + failure.getMessage(), failure);
            }
        }
        protected abstract ActionFabric.ActionObservation perform(ActionFabric.ActionRequest request) throws Exception;
    }

    private static final class AuditBrain implements CognitiveWorkerRuntime.Brain {
        @Override public CognitiveWorkerRuntime.Thought think(CognitiveWorkerRuntime.CognitiveContext context) {
            if (!context.memory().containsKey("defaultBranch")) {
                return new CognitiveWorkerRuntime.Thought(ACTION_METADATA, Map.of(),
                        "observe repository metadata before resolving an immutable audit head");
            }
            if (!context.memory().containsKey("commitSha")) {
                return new CognitiveWorkerRuntime.Thought(ACTION_HEAD,
                        Map.of("defaultBranch", context.memory().get("defaultBranch")),
                        "resolve the observed default branch to an immutable commit SHA");
            }
            if (!context.memory().containsKey("selectedPaths")) {
                return new CognitiveWorkerRuntime.Thought(ACTION_TREE,
                        Map.of("commitSha", context.memory().get("commitSha")),
                        "observe repository structure and select a bounded auditable content set");
            }
            List<String> selected = selected(context.memory().get("selectedPaths"));
            long alreadyRead = context.history().stream()
                    .filter(cycle -> ACTION_CONTENT.equals(cycle.thought().actionRef()))
                    .count();
            if (alreadyRead >= selected.size()) throw new IllegalStateException("audit brain advanced beyond selected content set");
            String path = selected.get((int) alreadyRead);
            return new CognitiveWorkerRuntime.Thought(ACTION_CONTENT,
                    Map.of("commitSha", context.memory().get("commitSha"), "path", path),
                    "inspect the next bounded repository file and observe its audit signals");
        }

        @Override public CognitiveWorkerRuntime.Reflection reflect(CognitiveWorkerRuntime.CognitiveContext context,
                                                                    ActionFabric.ActionObservation observation) {
            if (!observation.success()) {
                return CognitiveWorkerRuntime.Reflection.failed("repository audit action failed: " + observation.summary());
            }
            if (!ACTION_CONTENT.equals(observation.actionRef())) {
                return CognitiveWorkerRuntime.Reflection.continueWith("required repository identity/context observed; continue audit");
            }
            List<String> selected = selected(context.memory().get("selectedPaths"));
            long priorContent = context.history().stream()
                    .filter(cycle -> ACTION_CONTENT.equals(cycle.thought().actionRef()))
                    .count();
            if (priorContent + 1 >= selected.size()) {
                return CognitiveWorkerRuntime.Reflection.complete("all bounded repository content actions observed successfully");
            }
            return CognitiveWorkerRuntime.Reflection.continueWith("content observation recorded; inspect next selected file");
        }
    }

    private AuditStats summarize(CognitiveWorkerRuntime.Outcome outcome) {
        AuditStats stats = new AuditStats();
        stats.repositoryFilesObserved = parseInt(outcome.memory().get("repositoryFilesObserved"));
        for (CognitiveWorkerRuntime.Cycle cycle : outcome.cycles()) {
            if (!ACTION_CONTENT.equals(cycle.thought().actionRef()) || !cycle.observation().success()) continue;
            Map<String, String> out = cycle.observation().outputs();
            String path = out.get("path");
            if ("false".equalsIgnoreCase(out.get("readable"))) {
                stats.unreadableContentFiles++;
                if (path != null && !path.isBlank()) stats.skippedPaths.add(path);
                continue;
            }
            int bytes = parseInt(out.get("bytes"));
            if (stats.contentBytesRead + bytes > MAX_BYTES) {
                stats.unreadableContentFiles++;
                if (path != null && !path.isBlank()) stats.skippedPaths.add(path);
                continue;
            }
            stats.contentFilesRead++;
            stats.contentBytesRead += bytes;
            stats.sotSignals += truth(out.get("sot"));
            stats.documentFilesRead += truth(out.get("docs"));
            stats.sourceFilesRead += truth(out.get("source"));
            stats.testFilesRead += truth(out.get("tests"));
            stats.todo += parseInt(out.get("todo"));
            stats.conflict += parseInt(out.get("conflict"));
            if (path != null && !path.isBlank()) stats.observedPaths.add(path);
        }
        stats.gatewayCrossings = (int) outcome.cycles().stream()
                .flatMap(cycle -> cycle.observation().evidenceReferences().stream())
                .filter(ref -> ref.startsWith("gateway-egress:"))
                .count();
        return stats;
    }

    private ActionFabric.ActionObservation success(String actionRef, String summary, Map<String, String> outputs,
                                                    GatewayEgressClient.EgressResponse response) {
        List<String> refs = response.provenance().isBlank() ? List.of() : List.of(response.provenance());
        return ActionFabric.ActionObservation.success(actionRef, summary, outputs, refs);
    }

    private GatewayEgressClient.EgressResponse get(String path, String accept) throws Exception {
        GatewayEgressClient.EgressResponse response = egress.get(path, accept);
        if (response.denied()) throw new SecurityException("Gateway egress denied: " + response.denialReason());
        return response;
    }

    private static void requireHttp(GatewayEgressClient.EgressResponse response, int expected, String operation) {
        if (response.statusCode() != expected) {
            throw new IllegalStateException(operation + " HTTP " + response.statusCode());
        }
    }

    private static String findings(AuditStats stats) {
        List<String> values = new ArrayList<>();
        if (stats.conflict > 0) values.add("MERGE_CONFLICT_MARKERS=" + stats.conflict);
        if (stats.todo > 0) values.add("TODO_FIXME_MARKERS=" + stats.todo);
        if (stats.sotSignals == 0) values.add("NO_SOT_MARKER_IN_AUDITED_CONTENT");
        if (stats.unreadableContentFiles > 0) {
            values.add("UNREADABLE_CONTENT_FILES=" + stats.unreadableContentFiles);
        }
        return values.isEmpty() ? "NONE" : String.join(" | ", values);
    }

    private static Map<String, String> unreadableOutputs(String path, int statusCode) {
        return Map.of(
                "path", path,
                "readable", "false",
                "httpStatus", Integer.toString(statusCode),
                "bytes", "0",
                "sot", "false",
                "docs", "false",
                "source", "false",
                "tests", "false",
                "todo", "0",
                "conflict", "0");
    }

    private static List<String> paths(String json) {
        List<String> out = new ArrayList<>();
        Matcher matcher = TREE_PATH.matcher(json == null ? "" : json);
        while (matcher.find()) out.add(matcher.group(1));
        return out;
    }

    private static List<String> select(List<String> paths) {
        List<String> priority = new ArrayList<>(), rest = new ArrayList<>();
        for (String path : paths) {
            String lower = path.toLowerCase();
            if (!auditable(lower)) continue;
            if (lower.contains("sot") || lower.contains("architecture") || lower.contains("boundary")
                    || lower.contains("ontology") || lower.equals("readme.md")) priority.add(path);
            else rest.add(path);
        }
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        selected.addAll(priority);
        selected.addAll(rest);
        return new ArrayList<>(selected);
    }

    private static List<String> selected(String encoded) {
        if (encoded == null || encoded.isBlank()) return List.of();
        return List.of(encoded.split(Pattern.quote(PATH_SEPARATOR), -1)).stream().filter(v -> !v.isBlank()).toList();
    }

    private static boolean auditable(String lower) {
        return lower.endsWith(".md") || lower.endsWith(".txt") || lower.endsWith(".java") || lower.endsWith(".kt")
                || lower.endsWith(".py") || lower.endsWith(".js") || lower.endsWith(".ts") || lower.endsWith(".go")
                || lower.endsWith(".rs") || lower.endsWith(".yml") || lower.endsWith(".yaml") || lower.endsWith(".json");
    }

    private static String requireRepository(String repository) {
        String value = require(repository, "repository").replaceAll("\\.git$", "");
        if (!value.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("repository must be owner/repo");
        return value;
    }

    private static String requiredInput(ActionFabric.ActionRequest request, String name) {
        return require(request.inputs().get(name), "action input " + name);
    }

    private static String capture(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value == null ? "" : value);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String encodePath(String path) {
        StringBuilder out = new StringBuilder();
        for (String part : path.split("/")) {
            if (!out.isEmpty()) out.append('/');
            out.append(encode(part));
        }
        return out.toString();
    }

    private static int count(String value, String needle) {
        int count = 0, index = 0;
        while ((index = value.indexOf(needle, index)) >= 0) { count++; index += needle.length(); }
        return count;
    }

    private static int parseInt(String value) {
        try { return Integer.parseInt(value == null ? "0" : value); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static int truth(String value) { return Boolean.parseBoolean(value) ? 1 : 0; }
    private static String bool(boolean value) { return Boolean.toString(value); }

    private static WorkerResult failed(String workId, String repository, String reason, Instant at) {
        return new WorkerResult("RepositoryAuditWorker", "FAILED",
                "Repository Audit Report\ntask=" + workId + "\nrepository=" + String.valueOf(repository)
                        + "\nexecutionModel=cognitive-action-fabric\nobservedAt=" + at
                        + "\nverdict=FAILED\nreason=" + reason + "\n", at);
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
        return value.trim();
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value.trim();
    }

    private static final class AuditStats {
        int repositoryFilesObserved;
        int contentFilesRead;
        int unreadableContentFiles;
        int contentBytesRead;
        int sotSignals;
        int documentFilesRead;
        int sourceFilesRead;
        int testFilesRead;
        int todo;
        int conflict;
        int gatewayCrossings;
        final List<String> observedPaths = new ArrayList<>();
        final List<String> skippedPaths = new ArrayList<>();
    }
}
