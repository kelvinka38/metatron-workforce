package com.metatron.workforce.management;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.action.ActionFabric;
import com.metatron.workforce.action.ActionJournal;
import com.metatron.workforce.action.CognitiveWorkerRuntime;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.runtime.RepositoryCredentialAuthority;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Founder-approved bounded GitHub mutation executed by an assigned Cognitive Worker.
 *
 * <p>The capability remains the Management contract, but the Worker no longer receives one opaque
 * execute() effect. It sees a governed Action catalog and advances through a bounded
 * think -> act -> observe -> reflect loop. Each GitHub effect is separately authorized by the
 * Action Fabric and durably journaled. Main is never mutated or merged by this capability.</p>
 */
@Component
public final class RepositoryPullRequestAutonomousCapability implements AutonomousExecutionCapability {
    public static final String CAPABILITY = "repository.pr.propose";
    public static final String WORKER_ID = "WORKER-REPOSITORY-PR-PROPOSER";
    public static final String AUTHORITY_REFERENCE = "policy:founder-autonomy-gap-matrix-pr:v1";
    public static final String AUTHORIZATION_REFERENCE = "authorization:founder-autonomy-gap-matrix-pr:v1";
    public static final String ALLOWED_REPOSITORY = "kelvinka38/metatron-workforce";
    public static final String ALLOWED_PATH = "docs/AUTONOMY_CLOSURE/CURRENT_STATE_AND_GAP_MATRIX.md";
    static final String UNSET_SENTINEL = "GS2_AUTONOMOUS_PROBE=UNSET";
    static final String PROBE_PREFIX = "GS2_AUTONOMOUS_PROBE=";

    static final String ACTION_READ_MAIN = "github.repository.main-ref.read";
    static final String ACTION_READ_SOURCE = "github.repository.approved-file.read";
    static final String ACTION_ENSURE_BRANCH = "github.repository.proposal-branch.ensure";
    static final String ACTION_WRITE_PROBE = "github.repository.approved-file.propose";
    static final String ACTION_ENSURE_PR = "github.repository.pull-request.ensure";

    private final HttpClient http;
    private final ObjectMapper json;
    private final String apiBase;
    private final String token;

    @Autowired
    public RepositoryPullRequestAutonomousCapability(ObjectMapper json, RepositoryCredentialAuthority repositoryCredentials) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build(), json,
                "https://api.github.com", repositoryCredentials.tokenOrEmpty());
    }

    RepositoryPullRequestAutonomousCapability(HttpClient http, ObjectMapper json, String apiBase, String token) {
        this.http = Objects.requireNonNull(http);
        this.json = Objects.requireNonNull(json);
        this.apiBase = Objects.requireNonNull(apiBase).replaceAll("/+$", "");
        this.token = token == null ? "" : token.trim();
    }

    @Override public String capabilityRef() { return CAPABILITY; }
    @Override public String capabilityDescription() {
        return CAPABILITY + " — Cognitive Worker + governed Action Fabric; bounded GS2 proposal PR; never merges";
    }
    @Override public String authorityReference() { return AUTHORITY_REFERENCE; }
    @Override public String authorizationReference() { return AUTHORIZATION_REFERENCE; }
    @Override public boolean supportsWorker(String workerId) { return WORKER_ID.equals(workerId); }

    @Override
    public CapabilityResult execute(CapabilityRequest request) {
        requireGovernance(request);
        if (token.isBlank()) throw new SecurityException("GITHUB_TOKEN required for governed PR proposal");

        ActionFabric fabric = new ActionFabric(List.of(
                readMainAction(), readSourceAction(), ensureBranchAction(), writeProbeAction(), ensurePullRequestAction()));
        CognitiveWorkerRuntime runtime = new CognitiveWorkerRuntime(
                fabric, ActionJournal.runtimeEvidenceJournal(), 8);
        String probe = shortHash(request.idempotencyKey());
        String branch = "autonomy/gs2-" + probe;
        CognitiveWorkerRuntime.Outcome outcome = runtime.execute(
                request.allocatedWorkerId(),
                request.assignmentReference(),
                request.authorizationReference(),
                request.objectiveId(),
                request.workSpec(),
                request.idempotencyKey(),
                new PullRequestBrain(probe, branch));

        if (!outcome.success()) {
            throw new IllegalStateException("governed GitHub PR proposal failed: " + outcome.summary());
        }
        String prNumber = outcome.memory().getOrDefault("prNumber", "");
        String prUrl = outcome.memory().getOrDefault("prUrl", "");
        if (prNumber.isBlank() || prUrl.isBlank()) {
            throw new IllegalStateException("cognitive worker completed without PR identity");
        }

        List<String> evidence = new ArrayList<>(outcome.evidenceReferences());
        evidence.add("github-pr:" + prUrl);
        evidence.add("github-pr-number:" + prNumber);
        evidence.add("github-branch:" + branch);
        evidence.add("github-changed-path:" + ALLOWED_PATH);
        evidence.add("github-gs2-probe:" + probe);
        evidence.add("github-merge-performed:false");
        evidence.add("github-idempotency:" + request.idempotencyKey());
        evidence.add("cognitive-action-count:" + outcome.cycles().size());
        return new CapabilityResult(true, request.allocatedWorkerId(), request.assignmentReference(),
                "work:repository-pr:" + request.objectiveId() + ":" + request.workSpec().stepId(), evidence,
                "Cognitive Worker opened governed unmerged PR #" + prNumber
                        + " after " + outcome.cycles().size() + " authorized actions");
    }

    private ActionFabric.Action readMainAction() {
        return new BoundedAction(ACTION_READ_MAIN, ActionFabric.Consequence.READ_ONLY) {
            @Override protected ActionFabric.ActionObservation perform(ActionFabric.ActionRequest request) throws Exception {
                JsonNode ref = getJson("/repos/" + ALLOWED_REPOSITORY + "/git/ref/heads/main");
                String baseSha = ref.at("/object/sha").asText();
                if (baseSha.isBlank()) throw new IllegalStateException("GitHub main ref missing SHA");
                return ActionFabric.ActionObservation.success(actionRef(), "read canonical main ref",
                        Map.of("baseSha", baseSha), List.of("github-base-sha:" + baseSha));
            }
        };
    }

    private ActionFabric.Action readSourceAction() {
        return new BoundedAction(ACTION_READ_SOURCE, ActionFabric.Consequence.READ_ONLY) {
            @Override protected ActionFabric.ActionObservation perform(ActionFabric.ActionRequest request) throws Exception {
                JsonNode source = getJson("/repos/" + ALLOWED_REPOSITORY + "/contents/"
                        + encodePath(ALLOWED_PATH) + "?ref=main");
                String sourceSha = source.path("sha").asText();
                String original = decodeContent(source);
                if (sourceSha.isBlank() || original.isBlank()) throw new IllegalStateException("GS2 mutation source unavailable");
                int first = original.indexOf(UNSET_SENTINEL);
                if (first < 0 || first != original.lastIndexOf(UNSET_SENTINEL)) {
                    throw new IllegalStateException("canonical GS2 source must contain exactly one UNSET sentinel");
                }
                return ActionFabric.ActionObservation.success(actionRef(), "validated approved canonical mutation source",
                        Map.of("sourceSha", sourceSha, "sourceValidated", "true"),
                        List.of("github-source-sha:" + sourceSha, "github-approved-path:" + ALLOWED_PATH));
            }
        };
    }

    private ActionFabric.Action ensureBranchAction() {
        return new BoundedAction(ACTION_ENSURE_BRANCH, ActionFabric.Consequence.MUTATING) {
            @Override protected ActionFabric.ActionObservation perform(ActionFabric.ActionRequest request) throws Exception {
                String branch = requiredInput(request, "branch");
                String baseSha = requiredInput(request, "baseSha");
                requireProposalBranch(branch);
                HttpResponse<String> existing = send("GET", "/repos/" + ALLOWED_REPOSITORY
                        + "/git/ref/heads/" + encodePath(branch), null);
                if (existing.statusCode() != 200) {
                    if (existing.statusCode() != 404) throw failure("read branch", existing);
                    String body = json.createObjectNode().put("ref", "refs/heads/" + branch)
                            .put("sha", baseSha).toString();
                    sendJson("POST", "/repos/" + ALLOWED_REPOSITORY + "/git/refs", body, 201);
                }
                return ActionFabric.ActionObservation.success(actionRef(), "proposal branch ready",
                        Map.of("branchReady", "true"),
                        List.of("github-branch:" + branch, "github-branch-base-sha:" + baseSha));
            }
        };
    }

    private ActionFabric.Action writeProbeAction() {
        return new BoundedAction(ACTION_WRITE_PROBE, ActionFabric.Consequence.MUTATING) {
            @Override protected ActionFabric.ActionObservation perform(ActionFabric.ActionRequest request) throws Exception {
                String branch = requiredInput(request, "branch");
                String probe = requireProbe(requiredInput(request, "probe"));
                requireProposalBranch(branch);
                JsonNode branchFile = getJson("/repos/" + ALLOWED_REPOSITORY + "/contents/"
                        + encodePath(ALLOWED_PATH) + "?ref=" + encode(branch));
                String branchText = decodeContent(branchFile);
                if (!hasProbe(branchText, probe)) {
                    String mutated = applyProbe(branchText, probe);
                    String body = json.createObjectNode()
                            .put("message", "test(autonomy): record GS2 autonomous mutation probe")
                            .put("content", Base64.getEncoder().encodeToString(mutated.getBytes(StandardCharsets.UTF_8)))
                            .put("sha", branchFile.path("sha").asText())
                            .put("branch", branch).toString();
                    sendJson("PUT", "/repos/" + ALLOWED_REPOSITORY + "/contents/" + encodePath(ALLOWED_PATH),
                            body, 200, 201);
                }
                return ActionFabric.ActionObservation.success(actionRef(), "approved file proposal materialized",
                        Map.of("probeWritten", "true"),
                        List.of("github-changed-path:" + ALLOWED_PATH, "github-gs2-probe:" + probe));
            }
        };
    }

    private ActionFabric.Action ensurePullRequestAction() {
        return new BoundedAction(ACTION_ENSURE_PR, ActionFabric.Consequence.MUTATING) {
            @Override protected ActionFabric.ActionObservation perform(ActionFabric.ActionRequest request) throws Exception {
                String branch = requiredInput(request, "branch");
                String probe = requireProbe(requiredInput(request, "probe"));
                requireProposalBranch(branch);
                JsonNode pr = findOpenPull(branch);
                if (pr == null) {
                    String body = json.createObjectNode()
                            .put("title", "test(autonomy): GS2 governed mutation probe " + probe)
                            .put("head", branch)
                            .put("base", "main")
                            .put("body", "Golden Slice 2 governed mutation proposal. Cognitive Worker used the Action Fabric to change only the approved Autonomy Closure gap-matrix sentinel. Human approval is required before merge; no merge action exists in the Worker catalog.")
                            .toString();
                    pr = sendJson("POST", "/repos/" + ALLOWED_REPOSITORY + "/pulls", body, 201);
                }
                int number = pr.path("number").asInt();
                String htmlUrl = pr.path("html_url").asText();
                if (number <= 0 || htmlUrl.isBlank()) throw new IllegalStateException("GitHub PR response incomplete");
                if (pr.path("merged").asBoolean(false)) throw new SecurityException("Golden Slice 2 PR unexpectedly merged");
                return ActionFabric.ActionObservation.success(actionRef(), "governed proposal PR ready",
                        Map.of("prNumber", Integer.toString(number), "prUrl", htmlUrl),
                        List.of("github-pr:" + htmlUrl, "github-pr-number:" + number,
                                "github-pr-state:" + pr.path("state").asText(), "github-merge-performed:false"));
            }
        };
    }

    private abstract static class BaseAction implements ActionFabric.Action {
        private final String ref;
        private final ActionFabric.Consequence consequence;

        BaseAction(String ref, ActionFabric.Consequence consequence) {
            this.ref = ref;
            this.consequence = consequence;
        }
        @Override public final String actionRef() { return ref; }
        @Override public final ActionFabric.Consequence consequence() { return consequence; }
        @Override public final Set<String> allowedWorkers() { return Set.of(WORKER_ID); }
        @Override public final Set<String> acceptedAuthorizations() { return Set.of(AUTHORIZATION_REFERENCE); }
    }

    private abstract class BoundedAction extends BaseAction {
        BoundedAction(String ref, ActionFabric.Consequence consequence) { super(ref, consequence); }

        @Override
        public final ActionFabric.ActionObservation invoke(ActionFabric.ActionRequest request) {
            try {
                return perform(request);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(actionRef() + " interrupted", interrupted);
            } catch (Exception failure) {
                throw new IllegalStateException(actionRef() + " failed: " + failure.getMessage(), failure);
            }
        }

        protected abstract ActionFabric.ActionObservation perform(ActionFabric.ActionRequest request) throws Exception;
    }

    private static final class PullRequestBrain implements CognitiveWorkerRuntime.Brain {
        private final String probe;
        private final String branch;

        private PullRequestBrain(String probe, String branch) {
            this.probe = requireProbe(probe);
            this.branch = branch;
        }

        @Override
        public CognitiveWorkerRuntime.Thought think(CognitiveWorkerRuntime.CognitiveContext context) {
            Map<String, String> memory = context.memory();
            if (!memory.containsKey("baseSha")) {
                return new CognitiveWorkerRuntime.Thought(ACTION_READ_MAIN, Map.of(),
                        "canonical base identity is required before any proposal mutation");
            }
            if (!"true".equals(memory.get("sourceValidated"))) {
                return new CognitiveWorkerRuntime.Thought(ACTION_READ_SOURCE, Map.of(),
                        "approved path and sentinel must be validated before branch creation");
            }
            if (!"true".equals(memory.get("branchReady"))) {
                return new CognitiveWorkerRuntime.Thought(ACTION_ENSURE_BRANCH,
                        Map.of("branch", branch, "baseSha", memory.get("baseSha")),
                        "proposal effect requires an Objective-scoped branch anchored to observed main");
            }
            if (!"true".equals(memory.get("probeWritten"))) {
                return new CognitiveWorkerRuntime.Thought(ACTION_WRITE_PROBE,
                        Map.of("branch", branch, "probe", probe),
                        "approved sentinel mutation has not yet been observed on the proposal branch");
            }
            return new CognitiveWorkerRuntime.Thought(ACTION_ENSURE_PR,
                    Map.of("branch", branch, "probe", probe),
                    "a reviewable unmerged Pull Request is required to complete the bounded mutation");
        }

        @Override
        public CognitiveWorkerRuntime.Reflection reflect(CognitiveWorkerRuntime.CognitiveContext context,
                                                          ActionFabric.ActionObservation observation) {
            if (!observation.success()) {
                return CognitiveWorkerRuntime.Reflection.failed(
                        "tool observation failed at " + observation.actionRef() + ": " + observation.summary());
            }
            if (ACTION_ENSURE_PR.equals(observation.actionRef())) {
                if (observation.outputs().getOrDefault("prUrl", "").isBlank()) {
                    return CognitiveWorkerRuntime.Reflection.failed("PR action succeeded without reviewable PR URL");
                }
                return CognitiveWorkerRuntime.Reflection.complete("reviewable unmerged PR observed; mutation objective satisfied");
            }
            return CognitiveWorkerRuntime.Reflection.continueWith(
                    "observed " + observation.actionRef() + " successfully; determine next required action");
        }
    }

    private void requireGovernance(CapabilityRequest request) {
        if (!request.allocated() || !request.dispatchBound()) {
            throw new SecurityException("governed allocation and durable dispatch required");
        }
        if (request.workSpec().consequence() != ExecutionWorkSpec.Consequence.MUTATING) {
            throw new SecurityException("repository.pr.propose requires MUTATING work");
        }
        String target = normalizeRepo(request.workSpec().target());
        if (!ALLOWED_REPOSITORY.equalsIgnoreCase(target)) {
            throw new SecurityException("repository outside approved Golden Slice 2 scope");
        }
        if (!AUTHORIZATION_REFERENCE.equals(request.authorizationReference())) {
            throw new SecurityException("repository mutation authorization mismatch");
        }
        if (!WORKER_ID.equals(request.allocatedWorkerId())) {
            throw new SecurityException("repository mutation worker mismatch");
        }
    }

    static String applyProbe(String original, String probe) {
        Objects.requireNonNull(original, "original");
        String normalizedProbe = requireProbe(probe);
        int first = original.indexOf(UNSET_SENTINEL);
        int last = original.lastIndexOf(UNSET_SENTINEL);
        if (first < 0 || first != last) {
            throw new IllegalStateException("canonical GS2 source must contain exactly one UNSET sentinel");
        }
        return original.substring(0, first) + PROBE_PREFIX + normalizedProbe
                + original.substring(first + UNSET_SENTINEL.length());
    }

    static boolean hasProbe(String text, String probe) {
        return text != null && text.contains(PROBE_PREFIX + requireProbe(probe))
                && !text.contains(UNSET_SENTINEL);
    }

    private static String requireProbe(String probe) {
        String value = probe == null ? "" : probe.trim().toLowerCase(Locale.ROOT);
        if (!value.matches("[0-9a-f]{16}")) throw new IllegalArgumentException("GS2 probe must be 16 lowercase hex chars");
        return value;
    }

    private static void requireProposalBranch(String branch) {
        if (branch == null || !branch.matches("autonomy/gs2-[0-9a-f]{16}")) {
            throw new SecurityException("proposal branch outside bounded GS2 namespace");
        }
    }

    private static String requiredInput(ActionFabric.ActionRequest request, String name) {
        String value = request.inputs().get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("action input required: " + name);
        return value.trim();
    }

    private JsonNode findOpenPull(String branch) throws Exception {
        String head = "kelvinka38:" + branch;
        JsonNode pulls = getJson("/repos/" + ALLOWED_REPOSITORY
                + "/pulls?state=open&base=main&head=" + encode(head));
        return pulls.isArray() && !pulls.isEmpty() ? pulls.get(0) : null;
    }

    private JsonNode getJson(String path) throws Exception {
        HttpResponse<String> response = send("GET", path, null);
        if (response.statusCode() != 200) throw failure("GET " + path, response);
        return json.readTree(response.body());
    }

    private JsonNode sendJson(String method, String path, String body, int... accepted) throws Exception {
        HttpResponse<String> response = send(method, path, body);
        for (int status : accepted) if (response.statusCode() == status) return json.readTree(response.body());
        throw failure(method + " " + path, response);
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(apiBase + path))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "metatron-workforce-action-fabric-point4");
        if (!token.isBlank()) builder.header("Authorization", "Bearer " + token);
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static IllegalStateException failure(String operation, HttpResponse<String> response) {
        String body = response.body() == null ? "" : response.body();
        if (body.length() > 500) body = body.substring(0, 500);
        return new IllegalStateException(operation + " HTTP " + response.statusCode() + " " + body);
    }

    private static String decodeContent(JsonNode node) {
        String encoded = node.path("content").asText().replace("\n", "");
        return encoded.isBlank() ? "" : new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    private static String normalizeRepo(String target) {
        if (target == null) return "";
        String value = target.trim();
        if (value.startsWith("https://github.com/")) value = value.substring("https://github.com/".length());
        return value.replaceAll("\\.git$", "");
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest).substring(0, 16).toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String encodePath(String value) {
        return java.util.Arrays.stream(value.split("/"))
                .map(RepositoryPullRequestAutonomousCapability::encode)
                .reduce((a, b) -> a + "/" + b).orElse("");
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value.trim();
    }
}
