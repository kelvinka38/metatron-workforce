package com.metatron.workforce.management;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
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
 * This adapter can only repair the stale Autonomy Closure gap matrix in metatron-workforce and
 * open a pull request. It deliberately exposes no merge operation. The durable Objective/Assignment/
 * Authorization/Execution boundaries remain owned by Workforce; GitHub is only the governed effect.
 */
@Component
public final class RepositoryPullRequestAutonomousCapability implements AutonomousExecutionCapability {
    public static final String CAPABILITY = "repository.pr.propose";
    public static final String WORKER_ID = "WORKER-REPOSITORY-PR-PROPOSER";
    public static final String AUTHORITY_REFERENCE = "policy:founder-autonomy-gap-matrix-pr:v1";
    public static final String AUTHORIZATION_REFERENCE = "authorization:founder-autonomy-gap-matrix-pr:v1";
    public static final String ALLOWED_REPOSITORY = "kelvinka38/metatron-workforce";
    public static final String ALLOWED_PATH = "docs/AUTONOMY_CLOSURE/CURRENT_STATE_AND_GAP_MATRIX.md";

    private final HttpClient http;
    private final ObjectMapper json;
    private final String apiBase;
    private final String token;

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
        return CAPABILITY + " — bounded repair of Autonomy Closure gap matrix; opens PR only; never merges";
    }
    @Override public String authorityReference() { return AUTHORITY_REFERENCE; }
    @Override public String authorizationReference() { return AUTHORIZATION_REFERENCE; }
    @Override public boolean supportsWorker(String workerId) { return WORKER_ID.equals(workerId); }

    @Override
    public CapabilityResult execute(CapabilityRequest request) {
        requireGovernance(request);
        if (token.isBlank()) throw new SecurityException("GITHUB_TOKEN required for governed PR proposal");
        try {
            String baseSha = getJson("/repos/" + ALLOWED_REPOSITORY + "/git/ref/heads/main").at("/object/sha").asText();
            if (baseSha.isBlank()) throw new IllegalStateException("GitHub main ref missing SHA");

            JsonNode source = getJson("/repos/" + ALLOWED_REPOSITORY + "/contents/" + encodePath(ALLOWED_PATH) + "?ref=main");
            String sourceSha = source.path("sha").asText();
            String encoded = source.path("content").asText().replace("\n", "");
            if (sourceSha.isBlank() || encoded.isBlank()) throw new IllegalStateException("gap matrix source unavailable");
            String original = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            String repaired = repair(original, baseSha);
            if (repaired.equals(original)) {
                throw new IllegalStateException("controlled gap-matrix defect is no longer present; refuse unrelated mutation");
            }

            String branch = "autonomy/gs2-" + shortHash(request.idempotencyKey());
            ensureBranch(branch, baseSha);

            JsonNode branchFile = getJson("/repos/" + ALLOWED_REPOSITORY + "/contents/" + encodePath(ALLOWED_PATH)
                    + "?ref=" + encode(branch));
            String branchText = decodeContent(branchFile);
            if (!isRepaired(branchText)) {
                String body = json.createObjectNode()
                        .put("message", "docs(autonomy): refresh closure implementation baseline")
                        .put("content", Base64.getEncoder().encodeToString(repaired.getBytes(StandardCharsets.UTF_8)))
                        .put("sha", branchFile.path("sha").asText())
                        .put("branch", branch).toString();
                sendJson("PUT", "/repos/" + ALLOWED_REPOSITORY + "/contents/" + encodePath(ALLOWED_PATH), body, 200, 201);
            }

            JsonNode pr = findOpenPull(branch);
            if (pr == null) {
                String body = json.createObjectNode()
                        .put("title", "docs(autonomy): refresh closure implementation baseline")
                        .put("head", branch)
                        .put("base", "main")
                        .put("body", "Golden Slice 2 governed mutation. Workforce autonomously repairs the stale implementation baseline. Founder approval is required before merge; this capability has no merge operation.")
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
            evidence.add("github-merge-performed:false");
            evidence.add("github-idempotency:" + request.idempotencyKey());
            return new CapabilityResult(true, request.allocatedWorkerId(), request.assignmentReference(),
                    "work:repository-pr:" + request.objectiveId() + ":" + request.workSpec().stepId(), evidence,
                    "opened governed unmerged PR #" + number + " for controlled Autonomy Closure baseline repair");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GitHub mutation interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("governed GitHub PR proposal failed: " + e.getMessage(), e);
        }
    }

    private void requireGovernance(CapabilityRequest request) {
        if (!request.allocated() || !request.dispatchBound()) throw new SecurityException("governed allocation and durable dispatch required");
        if (request.workSpec().consequence() != ExecutionWorkSpec.Consequence.MUTATING) throw new SecurityException("repository.pr.propose requires MUTATING work");
        String target = normalizeRepo(request.workSpec().target());
        if (!ALLOWED_REPOSITORY.equalsIgnoreCase(target)) throw new SecurityException("repository outside approved Golden Slice 2 scope");
        if (!AUTHORIZATION_REFERENCE.equals(request.authorizationReference())) throw new SecurityException("repository mutation authorization mismatch");
        if (!WORKER_ID.equals(request.allocatedWorkerId())) throw new SecurityException("repository mutation worker mismatch");
    }

    private void ensureBranch(String branch, String baseSha) throws Exception {
        HttpResponse<String> existing = send("GET", "/repos/" + ALLOWED_REPOSITORY + "/git/ref/heads/" + encodePath(branch), null);
        if (existing.statusCode() == 200) return;
        if (existing.statusCode() != 404) throw failure("read branch", existing);
        String body = json.createObjectNode().put("ref", "refs/heads/" + branch).put("sha", baseSha).toString();
        sendJson("POST", "/repos/" + ALLOWED_REPOSITORY + "/git/refs", body, 201);
    }

    private JsonNode findOpenPull(String branch) throws Exception {
        String head = "kelvinka38:" + branch;
        JsonNode pulls = getJson("/repos/" + ALLOWED_REPOSITORY + "/pulls?state=open&base=main&head=" + encode(head));
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
        else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static IllegalStateException failure(String operation, HttpResponse<String> response) {
        String body = response.body() == null ? "" : response.body();
        if (body.length() > 500) body = body.substring(0, 500);
        return new IllegalStateException(operation + " HTTP " + response.statusCode() + " " + body);
    }

    static String repair(String original, String baseSha) {
        String out = original;
        out = out.replace("**Baseline:** `metatron-workforce/main` at or after `954b5647c114a8321e8beffc122c82784ac065dd`",
                "**Baseline:** `metatron-workforce/main` at `" + baseSha + "` (P1–P9/L9 implementation deployed; P10 acceptance pending)");
        out = out.replace("| Capability/Qualification/Availability/Assignment | Persistent Core state and APIs | Implemented substrate; allocation loop incomplete |",
                "| Capability/Qualification/Availability/Assignment | Persistent Core state, governed allocation and admission evidence | Implemented through governed allocation; P10 production proof pending |");
        out = out.replace("| Objective state | `ManagementAutonomyService` plus durable store boundary | Persistent primitive; general acceptance path incomplete |",
                "| Objective state | Durable acceptance, owner and outbox path plus persistent Management state | Implemented; P10 production proof pending |");
        out = out.replace("| Conversational acceptance | `HumanObjectiveIngressService.submit()` is synchronous | Does not meet accept-persist-detach contract |",
                "| Conversational acceptance | Durable accept-persist-detach ingress with fast Objective acknowledgement | Implemented; real-provider P10 proof pending |");
        out = out.replace("| Accountable ownership | Owner references and bounded tests exist | Needs transactional acceptance and fencing proof |",
                "| Accountable ownership | Transactional owner persistence plus Runner lease/fencing | Implemented; adversarial P10 proof pending |");
        out = out.replace("| Management Runner | Caller/test drives transitions | Missing persistent self-driving runner |",
                "| Management Runner | Persistent autonomous runner with wake/reconcile, lease and fencing | Implemented; adversarial P10 proof pending |");
        out = out.replace("| Work Graph | Execution specs can express steps/dependencies | Missing durable versioned DAG and general ready-set scheduler |",
                "| Work Graph | Durable versioned DAG, ready-set scheduler, joins and stale-version fencing | Implemented; elastic P10 proof pending |");
        out = out.replace("| Parallel scheduling | Test executors and async primitives exist | No production Workforce scheduler/fan-out/join proof |",
                "| Parallel scheduling | Ready-set scheduler with bounded concurrent dispatch and join semantics | Implemented; Golden Slice 4 proof pending |");
        out = out.replace("| Staffing | `StaffingService` represents requests/proposals/resolution/escalation | No autonomous source/admit/form/allocate loop |",
                "| Staffing | Governed autonomous staffing/formation policy integrated with allocation | Implemented for bounded capabilities; P10 staffing proof pending |");
        out = out.replace("| AI Worker formation | Core admission primitives exist | No governed dynamic formation path proved |",
                "| AI Worker formation | Governed Participant/Worker/Participation formation with capability, qualification and availability | Implemented for bounded capability formation; production scope remains evidence-bound |");
        out = out.replace("| Remote runtime | `RemoteRuntimeExecutor.executeAsync()` exists | Primitive not wired into Objective scheduler path |",
                "| Remote runtime | Execution attempts/runtime capacity bound into autonomous dispatch path | Implemented; failure-injection P10 proof pending |");
        out = out.replace("| Runtime recovery | Identity/state recovery tests exist | No unfinished Objective/Work lease recovery proof |",
                "| Runtime recovery | Durable execution attempts, runtime recovery and stale-attempt fencing | Implemented; Golden Slice 3 proof pending |");
        out = out.replace("| Authorization | Separation and fail-closed tests exist | Must be integrated into durable dispatch and revocation fencing |",
                "| Authorization | Fail-closed admission, durable dispatch binding and authority revocation fencing | Implemented; P10 production proof pending |");
        out = out.replace("| Observation closure | Capability-produced evidence can close bounded work | No independent general Observation criterion loop |",
                "| Observation closure | Durable criterion-level Observation boundary and completion gate | Implemented machinery; live authoritative verifiers and P10 evidence required |");
        out = out.replace("| Workplace/Meeting Room | Canonical product/design and implementation primitives exist | Full persistence and operational Objective integration incomplete |",
                "| Workplace/Meeting Room | Durable cross-channel Objective continuity, progress and delivery records | Integrated for Autonomy Closure scope; P10 cross-channel proof pending |");
        out = out.replace("| Economy | Budget/cost concepts and tests exist | No L9 production enforcement/observability proof |",
                "| Economy | Durable per-Objective cost, attempts, deadline and risk safety ledger | L9 controls implemented/deployed; formal P10 evidence pending |");
        out = out.replace("The current conversational path couples interaction lifetime to Intelligence and execution work. Telegram acknowledges the webhook through a bounded in-memory executor, but processing is still tied to process-local capacity. Objective submission is synchronized and execution steps are iterated sequentially. This architecture explains why a large task can remain slow or time out instead of becoming an independently managed Objective.\n\nProduction telemetry must quantify each latency contributor; the structural coupling itself is already established.",
                "The prior synchronous/process-local interaction coupling has been superseded by durable accept-persist-detach ingress and a persistent Management Runner. Remaining closure risk is no longer the existence of these primitives; it is production proof that a material Objective traverses them end-to-end without external orchestration. P10 Golden Slices and the 45-condition gate remain authoritative.");
        return out;
    }

    static boolean isRepaired(String text) {
        return text != null && text.contains("Persistent autonomous runner with wake/reconcile")
                && text.contains("Durable versioned DAG")
                && text.contains("L9 controls implemented/deployed")
                && !text.contains("Missing persistent self-driving runner");
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
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
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
                .map(RepositoryPullRequestAutonomousCapability::encode).reduce((a, b) -> a + "/" + b).orElse("");
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value.trim();
    }
}
