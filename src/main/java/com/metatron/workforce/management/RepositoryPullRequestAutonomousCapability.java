package com.metatron.workforce.management;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
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
import java.util.Objects;

/**
 * Founder-approved, tightly bounded Golden Slice 2 mutation capability.
 *
 * <p>The only permitted effect is to replace the stable GS2 UNSET sentinel in the canonical
 * Autonomy Closure gap matrix on an Objective-scoped proposal branch and open a Pull Request.
 * Canonical main stays unchanged until a Human explicitly merges. This keeps the original allowed
 * repository/path boundary while making the production slice repeatable.</p>
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

    private final HttpClient http;
    private final ObjectMapper json;
    private final String apiBase;
    private final String token;

    @Autowired
    public RepositoryPullRequestAutonomousCapability(ObjectMapper json) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build(), json,
                "https://api.github.com", env("GITHUB_TOKEN"));
    }

    RepositoryPullRequestAutonomousCapability(HttpClient http, ObjectMapper json, String apiBase, String token) {
        this.http = Objects.requireNonNull(http);
        this.json = Objects.requireNonNull(json);
        this.apiBase = Objects.requireNonNull(apiBase).replaceAll("/+$", "");
        this.token = token == null ? "" : token.trim();
    }

    @Override public String capabilityRef() { return CAPABILITY; }
    @Override public String capabilityDescription() {
        return CAPABILITY + " — bounded repeatable GS2 gap-matrix sentinel mutation; opens PR only; never merges";
    }
    @Override public String authorityReference() { return AUTHORITY_REFERENCE; }
    @Override public String authorizationReference() { return AUTHORIZATION_REFERENCE; }
    @Override public boolean supportsWorker(String workerId) { return WORKER_ID.equals(workerId); }

    @Override
    public CapabilityResult execute(CapabilityRequest request) {
        requireGovernance(request);
        if (token.isBlank()) throw new SecurityException("GITHUB_TOKEN required for governed PR proposal");
        try {
            String baseSha = getJson("/repos/" + ALLOWED_REPOSITORY + "/git/ref/heads/main")
                    .at("/object/sha").asText();
            if (baseSha.isBlank()) throw new IllegalStateException("GitHub main ref missing SHA");

            JsonNode source = getJson("/repos/" + ALLOWED_REPOSITORY + "/contents/"
                    + encodePath(ALLOWED_PATH) + "?ref=main");
            String sourceSha = source.path("sha").asText();
            String original = decodeContent(source);
            if (sourceSha.isBlank() || original.isBlank()) {
                throw new IllegalStateException("GS2 mutation source unavailable");
            }

            String probe = shortHash(request.idempotencyKey());
            String mutated = applyProbe(original, probe);
            String branch = "autonomy/gs2-" + probe;
            ensureBranch(branch, baseSha);

            JsonNode branchFile = getJson("/repos/" + ALLOWED_REPOSITORY + "/contents/"
                    + encodePath(ALLOWED_PATH) + "?ref=" + encode(branch));
            String branchText = decodeContent(branchFile);
            if (!hasProbe(branchText, probe)) {
                if (!branchText.contains(UNSET_SENTINEL)) {
                    throw new IllegalStateException("GS2 proposal branch source is outside bounded mutation state");
                }
                String body = json.createObjectNode()
                        .put("message", "test(autonomy): record GS2 autonomous mutation probe")
                        .put("content", Base64.getEncoder().encodeToString(mutated.getBytes(StandardCharsets.UTF_8)))
                        .put("sha", branchFile.path("sha").asText())
                        .put("branch", branch).toString();
                sendJson("PUT", "/repos/" + ALLOWED_REPOSITORY + "/contents/" + encodePath(ALLOWED_PATH),
                        body, 200, 201);
            }

            JsonNode pr = findOpenPull(branch);
            if (pr == null) {
                String body = json.createObjectNode()
                        .put("title", "test(autonomy): GS2 governed mutation probe " + probe)
                        .put("head", branch)
                        .put("base", "main")
                        .put("body", "Golden Slice 2 governed mutation proposal. Workforce changed only the approved Autonomy Closure gap-matrix sentinel. Human approval is required before merge; this capability exposes no merge operation.")
                        .toString();
                pr = sendJson("POST", "/repos/" + ALLOWED_REPOSITORY + "/pulls", body, 201);
            }

            int number = pr.path("number").asInt();
            String htmlUrl = pr.path("html_url").asText();
            if (number <= 0 || htmlUrl.isBlank()) throw new IllegalStateException("GitHub PR response incomplete");
            if (pr.path("merged").asBoolean(false)) throw new SecurityException("Golden Slice 2 PR unexpectedly merged");

            List<String> evidence = new ArrayList<>();
            evidence.add("github-pr:" + htmlUrl);
            evidence.add("github-pr-number:" + number);
            evidence.add("github-pr-state:" + pr.path("state").asText());
            evidence.add("github-branch:" + branch);
            evidence.add("github-base-sha:" + baseSha);
            evidence.add("github-changed-path:" + ALLOWED_PATH);
            evidence.add("github-gs2-probe:" + probe);
            evidence.add("github-merge-performed:false");
            evidence.add("github-idempotency:" + request.idempotencyKey());
            return new CapabilityResult(true, request.allocatedWorkerId(), request.assignmentReference(),
                    "work:repository-pr:" + request.objectiveId() + ":" + request.workSpec().stepId(), evidence,
                    "opened governed unmerged PR #" + number + " for repeatable GS2 mutation probe " + probe);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GitHub mutation interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("governed GitHub PR proposal failed: " + e.getMessage(), e);
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

    private void ensureBranch(String branch, String baseSha) throws Exception {
        HttpResponse<String> existing = send("GET", "/repos/" + ALLOWED_REPOSITORY
                + "/git/ref/heads/" + encodePath(branch), null);
        if (existing.statusCode() == 200) return;
        if (existing.statusCode() != 404) throw failure("read branch", existing);
        String body = json.createObjectNode().put("ref", "refs/heads/" + branch)
                .put("sha", baseSha).toString();
        sendJson("POST", "/repos/" + ALLOWED_REPOSITORY + "/git/refs", body, 201);
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
                .header("User-Agent", "metatron-workforce-autonomy-p10");
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
