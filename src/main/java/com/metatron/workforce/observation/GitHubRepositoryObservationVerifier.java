package com.metatron.workforce.observation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.management.RepositoryPullRequestAutonomousCapability;
import com.metatron.workforce.runtime.RepositoryCredentialAuthority;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Authoritative Observation adapter for repository Autonomy Closure work.
 * It performs a fresh GitHub read rather than treating execution success as Observation truth.
 */
@Component
public final class GitHubRepositoryObservationVerifier implements ObservationVerifier {
    private static final Pattern REPOSITORY = Pattern.compile("(?i)(?:https?://github\\.com/)?([a-z0-9_.-]+)/([a-z0-9_.-]+)");
    private static final Pattern PR_EVIDENCE = Pattern.compile("github-pr:https://github\\.com/([^/]+/[^/]+)/pull/(\\d+)");
    private static final Pattern PROBE_EVIDENCE = Pattern.compile("github-gs2-probe:([0-9a-f]{16})");
    private static final Pattern GS2_BRANCH = Pattern.compile("autonomy/gs2-([0-9a-f]{16})");
    private static final String UNSET_SENTINEL = "GS2_AUTONOMOUS_PROBE=UNSET";
    private static final String PROBE_PREFIX = "GS2_AUTONOMOUS_PROBE=";
    /**
     * Production incident (2026-09-23), same case build-and-deliver "Metatron Workforce Control Center":
     * the general-workspace DELIVER step successfully published a real reviewable PR
     * (workspace.github.pr.publish PASS), but this verifier's own independent fresh reads of that
     * just-created PR -- issued within seconds of publish, at Observation's own retry cadence -- hit
     * GitHub's read-after-write propagation lag on the PR/commit/files endpoints (the same class of lag
     * fixed for repository creation in the PRODUCE step's RepositoryWorkspaceMaterializationService, now
     * showing up one step later against a different endpoint). With zero retry inside a single {@link
     * #get} call, all of Observation's own outer bounded attempts (3) were exhausted purely on transient
     * 404s, permanently blocking an Objective whose delivery had genuinely and correctly succeeded.
     */
    private static final long[] READ_LAG_BACKOFF_MS = {500L, 1000L, 2000L};

    private final HttpClient http;
    private final ObjectMapper json;
    private final String apiBase;
    private final String token;

    /** Explicitly select the production constructor because a package-private test constructor also exists. */
    @Autowired
    public GitHubRepositoryObservationVerifier(ObjectMapper json, RepositoryCredentialAuthority repositoryCredentials) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build(), json,
                "https://api.github.com", repositoryCredentials.tokenOrEmpty());
    }

    GitHubRepositoryObservationVerifier(HttpClient http, ObjectMapper json, String apiBase, String token) {
        this.http = http;
        this.json = json;
        this.apiBase = apiBase.replaceAll("/+$", "");
        this.token = token == null ? "" : token.trim();
    }

    @Override
    public boolean supports(ObservationRequirement requirement) {
        return repository(requirement.target()) != null;
    }

    @Override
    public Optional<ObservationReport> observe(ObservationRequirement requirement,
                                               List<String> executionEvidenceReferences,
                                               Instant at) {
        String repo = repository(requirement.target());
        if (repo == null) return Optional.empty();
        try {
            List<PullEvidence> pulls = pullEvidences(executionEvidenceReferences);
            if (!pulls.isEmpty()) {
                if (executionEvidenceReferences.stream().anyMatch("github-general-proposal:true"::equals)) {
                    // Production incident (2026-09-24, case build-and-deliver "Metatron Workforce Control
                    // Center"): the Objective-wide evidence list is not only this Objective's own publication
                    // -- it also carries e.g. the Worker's runtime-constitution/standing references, which can
                    // name a PR the same Worker authored for an earlier, unrelated Objective in another
                    // repository. Taking the FIRST github-pr reference therefore bound Observation to that
                    // foreign PR and failed deterministically with a repository mismatch, even though the real
                    // proposal PR existed in the target repository. Bind to the most recent PR in the target.
                    PullEvidence pull = null;
                    for (PullEvidence candidate : pulls) {
                        if (candidate.repository().equalsIgnoreCase(repo)) pull = candidate;
                    }
                    if (pull == null) {
                        throw new SecurityException("general proposal evidence repository does not match Observation target: target="
                                + repo + " evidence=" + pulls.stream().map(PullEvidence::repository).distinct().toList());
                    }
                    return Optional.of(observeGeneralPull(requirement, executionEvidenceReferences, pull, at));
                }
                return Optional.of(observePull(requirement, executionEvidenceReferences, pulls.getFirst(), at));
            }
            return Optional.of(observeReadOnly(requirement, executionEvidenceReferences, repo, at));
        } catch (Exception failure) {
            // Root-cause fix (2026-09-23): AutonomousManagementRunner.observationFailureDetail() surfaces
            // report.observedState() to the Human as the whole diagnostic for a BLOCKED Objective -- every
            // other ObservationVerifier in this package puts its actual diagnostic there (see
            // GeneralWorkspaceObservationVerifier.report(), AutonomyRecoveryProbeObservationVerifier
            // .inconclusive()). This verifier instead hardcoded a generic "GitHub verification failed"
            // into observedState and buried the real exception message in variance(), a field nothing
            // reads. Every GitHub-observation INCONCLUSIVE therefore showed only that generic string,
            // never the actual HTTP status or exception -- the exact detail a Human needs to tell a
            // transient propagation-lag failure from a genuine mismatch was silently discarded.
            String diagnostic = failure.getClass().getSimpleName()
                    + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
            // The report id is per-observation (not one fixed ":failed" id): ObservationClosureService
            // ignores a re-recorded report whose id equals the current one, so a later, different failure
            // (e.g. after a Human resume grants a fresh budget) would otherwise never replace the stale
            // diagnostic a Human sees.
            return Optional.of(new ObservationReport(
                    "observation:github:" + requirement.requirementId() + ":failed:" + at.toEpochMilli(),
                    requirement.requirementId(), requirement.objectiveId(), requirement.target(),
                    "GitHub verification failed: " + diagnostic, "authoritative-github-api-read", at, at,
                    List.of("observation-error:" + failure.getClass().getSimpleName()), 0.0,
                    ObservationReport.Quality.INSUFFICIENT,
                    diagnostic, ObservationReport.CriterionResult.INCONCLUSIVE));
        }
    }

    private ObservationReport observeReadOnly(ObservationRequirement requirement,
                                                List<String> executionEvidenceReferences,
                                                String repo, Instant at) throws Exception {
        JsonNode metadata = get("/repos/" + repo);
        String branch = metadata.path("default_branch").asText();
        if (branch.isBlank()) throw new IllegalStateException("default branch missing");
        JsonNode commit = get("/repos/" + repo + "/commits/" + encode(branch));
        String sha = commit.path("sha").asText();
        if (sha.isBlank()) throw new IllegalStateException("commit SHA missing");

        boolean executionClosed = executionEvidenceReferences.stream().anyMatch(v -> v.startsWith("work:"))
                && executionEvidenceReferences.stream().anyMatch(v -> v.contains("RepositoryAuditWorker:PASS"));
        List<String> evidence = new ArrayList<>();
        evidence.add("github-observation:" + repo + "@" + sha);
        evidence.add("github-observation-default-branch:" + branch);
        evidence.add("github-observation-method:fresh-api-read");
        if (executionClosed) evidence.add("github-observation-execution-correlation:PASS");

        return new ObservationReport(
                "observation:github:" + requirement.requirementId() + ":" + sha.substring(0, Math.min(12, sha.length())),
                requirement.requirementId(), requirement.objectiveId(), requirement.target(),
                executionClosed ? "repository independently reachable at exact commit; governed audit execution correlation present"
                        : "repository independently reachable but governed audit execution correlation missing",
                "authoritative-github-api-read", at, at, evidence,
                executionClosed ? 0.98 : 0.60,
                executionClosed ? ObservationReport.Quality.HIGH : ObservationReport.Quality.INSUFFICIENT,
                executionClosed ? "" : "missing work:/RepositoryAuditWorker:PASS execution evidence",
                executionClosed ? ObservationReport.CriterionResult.PASS : ObservationReport.CriterionResult.INCONCLUSIVE);
    }

    private ObservationReport observeGeneralPull(ObservationRequirement requirement,
                                                  List<String> executionEvidenceReferences,
                                                  PullEvidence pull,
                                                  Instant at) throws Exception {
        String targetRepository = repository(requirement.target());
        if (targetRepository == null || !targetRepository.equalsIgnoreCase(pull.repository())) {
            throw new SecurityException("general proposal evidence repository does not match Observation target");
        }

        JsonNode pr = get("/repos/" + pull.repository() + "/pulls/" + pull.number());
        boolean open = "open".equalsIgnoreCase(pr.path("state").asText());
        boolean unmerged = !pr.path("merged").asBoolean(false) && pr.path("merged_at").isNull();
        String base = pr.at("/base/ref").asText();
        String head = pr.at("/head/ref").asText();
        String headSha = pr.at("/head/sha").asText();

        // Each fact is checked against the Objective's own attested values (not the first occurrence), for
        // the same reason the PR itself is bound to the target repository above: a foreign reference that
        // merely appears earlier in the Objective-wide evidence must never decide Observation.
        Set<String> expectedBases = evidenceValues(executionEvidenceReferences, "github-base-branch:");
        Set<String> expectedBranches = evidenceValues(executionEvidenceReferences, "github-branch:");
        Set<String> expectedSources = evidenceValues(executionEvidenceReferences, "github-source-sha:");
        Set<String> expectedRemoteCommits = evidenceValues(executionEvidenceReferences, "github-remote-commit:");
        Set<String> expectedPaths = evidenceValues(executionEvidenceReferences, "github-changed-path:");
        boolean noMergeClaim = executionEvidenceReferences.stream().anyMatch("github-merge-performed:false"::equals);

        JsonNode commit = get("/repos/" + pull.repository() + "/git/commits/" + headSha);
        String parentSha = commit.path("parents").isArray() && !commit.path("parents").isEmpty()
                ? commit.path("parents").get(0).path("sha").asText() : "";

        JsonNode files = get("/repos/" + pull.repository() + "/pulls/" + pull.number() + "/files?per_page=100");
        Set<String> actualPaths = new LinkedHashSet<>();
        if (files.isArray()) {
            for (JsonNode file : files) {
                String path = file.path("filename").asText("").trim();
                if (!path.isBlank()) actualPaths.add(path);
            }
        }

        boolean sourceBound = parentSha.matches("[0-9a-f]{40}") && expectedSources.contains(parentSha);
        boolean remoteCommitBound = headSha.matches("[0-9a-f]{40}") && expectedRemoteCommits.contains(headSha);
        boolean branchBound = !head.isBlank() && expectedBranches.contains(head)
                && head.startsWith("metatron/objective-");
        boolean baseBound = !base.isBlank() && expectedBases.contains(base);
        boolean pathsBound = !expectedPaths.isEmpty() && expectedPaths.size() <= 50
                && expectedPaths.equals(actualPaths);
        boolean pass = open && unmerged && sourceBound && remoteCommitBound && branchBound
                && baseBound && pathsBound && noMergeClaim;

        List<String> evidence = List.of(
                "github-general-pr-observation:https://github.com/" + pull.repository() + "/pull/" + pull.number(),
                "github-general-pr-observation-open:" + open,
                "github-general-pr-observation-unmerged:" + unmerged,
                "github-general-pr-observation-source-bound:" + sourceBound,
                "github-general-pr-observation-remote-commit-bound:" + remoteCommitBound,
                "github-general-pr-observation-branch-bound:" + branchBound,
                "github-general-pr-observation-base-bound:" + baseBound,
                "github-general-pr-observation-paths-bound:" + pathsBound,
                "github-general-pr-observation-fresh-api-read:true");
        return new ObservationReport(
                "observation:github-general-pr:" + pull.number() + ":" + requirement.requirementId(),
                requirement.requirementId(), requirement.objectiveId(), requirement.target(),
                pass
                        ? "fresh GitHub reads prove the reviewable proposal is source-bound, unmerged, and contains exactly the committed Objective path set"
                        : "general GitHub proposal does not satisfy the governed source/branch/path contract",
                "authoritative-github-api-read", at, at, evidence, 0.99,
                ObservationReport.Quality.HIGH,
                pass ? "" : "open=" + open + ",unmerged=" + unmerged + ",sourceBound=" + sourceBound
                        + ",remoteCommitBound=" + remoteCommitBound + ",branchBound=" + branchBound
                        + ",baseBound=" + baseBound + ",pathsBound=" + pathsBound + ",noMergeClaim=" + noMergeClaim,
                pass ? ObservationReport.CriterionResult.PASS : ObservationReport.CriterionResult.FAIL);
    }

    private ObservationReport observePull(ObservationRequirement requirement,
                                           List<String> executionEvidenceReferences,
                                           PullEvidence pull, Instant at) throws Exception {
        if (!RepositoryPullRequestAutonomousCapability.ALLOWED_REPOSITORY.equalsIgnoreCase(pull.repository())) {
            throw new SecurityException("PR evidence outside approved repository");
        }
        JsonNode pr = get("/repos/" + pull.repository() + "/pulls/" + pull.number());
        boolean open = "open".equalsIgnoreCase(pr.path("state").asText());
        boolean unmerged = !pr.path("merged").asBoolean(false) && pr.path("merged_at").isNull();
        String base = pr.at("/base/ref").asText();
        String head = pr.at("/head/ref").asText();
        JsonNode files = get("/repos/" + pull.repository() + "/pulls/" + pull.number() + "/files?per_page=100");
        boolean onlyAllowedFile = files.isArray() && files.size() == 1
                && RepositoryPullRequestAutonomousCapability.ALLOWED_PATH.equals(files.get(0).path("filename").asText());

        String mainContent = readContent(pull.repository(), RepositoryPullRequestAutonomousCapability.ALLOWED_PATH, "main");
        String branchContent = readContent(pull.repository(), RepositoryPullRequestAutonomousCapability.ALLOWED_PATH, head);
        String evidenceProbe = probeEvidence(executionEvidenceReferences);
        Matcher branchMatcher = GS2_BRANCH.matcher(head);
        String branchProbe = branchMatcher.matches() ? branchMatcher.group(1) : "";
        boolean probeBound = !branchProbe.isBlank() && branchProbe.equals(evidenceProbe);
        boolean mainFixtureValid = occurrences(mainContent, UNSET_SENTINEL) == 1;
        String expectedBranchContent = mainFixtureValid && probeBound
                ? replaceUniqueSentinel(mainContent, branchProbe) : "";
        boolean exactBoundedMutation = mainFixtureValid && probeBound && expectedBranchContent.equals(branchContent);
        boolean noMergeClaim = executionEvidenceReferences.stream().anyMatch("github-merge-performed:false"::equals);
        boolean pass = open && unmerged && "main".equals(base) && onlyAllowedFile
                && exactBoundedMutation && noMergeClaim;

        List<String> evidence = List.of(
                "github-pr-observation:https://github.com/" + pull.repository() + "/pull/" + pull.number(),
                "github-pr-observation-state:" + (open ? "open" : pr.path("state").asText()),
                "github-pr-observation-merged:" + !unmerged,
                "github-pr-observation-base:" + base,
                "github-pr-observation-head:" + head,
                "github-pr-observation-files:" + files.size(),
                "github-pr-observation-allowed-path-only:" + onlyAllowedFile,
                "github-pr-observation-main-fixture-valid:" + mainFixtureValid,
                "github-pr-observation-probe-bound:" + probeBound,
                "github-pr-observation-exact-bounded-mutation:" + exactBoundedMutation,
                "github-pr-observation-fresh-api-read:true");
        return new ObservationReport(
                "observation:github-pr:" + pull.number() + ":" + requirement.requirementId(),
                requirement.requirementId(), requirement.objectiveId(), requirement.target(),
                pass ? "fresh GitHub reads prove the open unmerged PR contains exactly the authorized GS2 sentinel mutation"
                        : "PR mutation does not satisfy the exact bounded Golden Slice 2 contract",
                "authoritative-github-api-read", at, at, evidence,
                0.99,
                ObservationReport.Quality.HIGH,
                pass ? "" : "open=" + open + ",unmerged=" + unmerged + ",base=" + base
                        + ",head=" + head + ",allowedFile=" + onlyAllowedFile
                        + ",mainFixtureValid=" + mainFixtureValid + ",probeBound=" + probeBound
                        + ",exactBoundedMutation=" + exactBoundedMutation + ",noMergeClaim=" + noMergeClaim,
                pass ? ObservationReport.CriterionResult.PASS : ObservationReport.CriterionResult.FAIL);
    }

    private String readContent(String repository, String path, String ref) throws Exception {
        JsonNode node = get("/repos/" + repository + "/contents/" + encodePath(path) + "?ref=" + encode(ref));
        String encoded = node.path("content").asText().replace("\n", "");
        return encoded.isBlank() ? "" : new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    private static String replaceUniqueSentinel(String mainContent, String probe) {
        if (occurrences(mainContent, UNSET_SENTINEL) != 1) {
            throw new IllegalStateException("canonical main must contain exactly one GS2 UNSET sentinel");
        }
        int index = mainContent.indexOf(UNSET_SENTINEL);
        return mainContent.substring(0, index) + PROBE_PREFIX + probe
                + mainContent.substring(index + UNSET_SENTINEL.length());
    }

    private static int occurrences(String value, String needle) {
        if (value == null || value.isEmpty() || needle.isEmpty()) return 0;
        int count = 0;
        int from = 0;
        while ((from = value.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    private static Set<String> evidenceValues(List<String> evidence, String prefix) {
        Set<String> values = new LinkedHashSet<>();
        for (String value : evidence) {
            if (value != null && value.startsWith(prefix)) {
                String extracted = value.substring(prefix.length()).trim();
                if (!extracted.isBlank()) values.add(extracted);
            }
        }
        return Set.copyOf(values);
    }

    private List<PullEvidence> pullEvidences(List<String> evidence) {
        List<PullEvidence> pulls = new ArrayList<>();
        for (String value : evidence) {
            Matcher matcher = PR_EVIDENCE.matcher(value == null ? "" : value.trim());
            if (matcher.matches()) pulls.add(new PullEvidence(matcher.group(1), Integer.parseInt(matcher.group(2))));
        }
        return pulls;
    }

    private static String probeEvidence(List<String> evidence) {
        for (String value : evidence) {
            Matcher matcher = PROBE_EVIDENCE.matcher(value == null ? "" : value.trim());
            if (matcher.matches()) return matcher.group(1);
        }
        return "";
    }

    private JsonNode get(String path) throws Exception {
        return get(path, 0);
    }

    /**
     * Production incident (2026-09-24, same case build-and-deliver "Metatron Workforce Control Center"):
     * after the read-lag fix, the Objective still reached observation-inconclusive on the published PR
     * criterion, with all three outer Observation attempts consumed within ~9 seconds of DELIVER -- far
     * faster than the 404 backoff alone allows -- i.e. a fast non-404 failure. Only 404 was retried, so any
     * other transient GitHub condition right after a burst of proposal writes (secondary rate limit 403,
     * 429, 5xx, a reset connection) failed every outer attempt instantly. Those are now retried with the
     * same bounded backoff, and a final failure names the endpoint and GitHub's own message instead of a
     * bare status code, so the durable blocker is diagnosable.
     */
    private JsonNode get(String path, int attempt) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(apiBase + path))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "metatron-workforce-observation");
        if (!token.isBlank()) request.header("Authorization", "Bearer " + token);
        HttpResponse<String> response;
        try {
            response = http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException transportFailure) {
            if (attempt < READ_LAG_BACKOFF_MS.length) {
                backoff(attempt);
                return get(path, attempt + 1);
            }
            throw new IllegalStateException("GitHub observation GET " + path + " transport failure: "
                    + transportFailure.getClass().getSimpleName()
                    + (transportFailure.getMessage() == null ? "" : ": " + transportFailure.getMessage()),
                    transportFailure);
        }
        if (transientFailure(response) && attempt < READ_LAG_BACKOFF_MS.length) {
            backoff(attempt);
            return get(path, attempt + 1);
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("GitHub observation HTTP " + response.statusCode()
                    + " GET " + path + githubMessage(response.body()));
        }
        return json.readTree(response.body());
    }

    private static boolean transientFailure(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status == 404 || status == 429 || status >= 500) return true;
        if (status != 403) return false;
        String body = response.body() == null ? "" : response.body().toLowerCase(java.util.Locale.ROOT);
        return response.headers().firstValue("retry-after").isPresent()
                || "0".equals(response.headers().firstValue("x-ratelimit-remaining").orElse(""))
                || body.contains("rate limit");
    }

    private static void backoff(int attempt) throws InterruptedException {
        try {
            Thread.sleep(READ_LAG_BACKOFF_MS[attempt]);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
    }

    private String githubMessage(String body) {
        if (body == null || body.isBlank()) return "";
        String message;
        try {
            message = json.readTree(body).path("message").asText("");
        } catch (Exception notJson) {
            message = body;
        }
        message = message.replace('\r', ' ').replace('\n', ' ').trim();
        if (message.isBlank()) return "";
        return ": " + (message.length() <= 200 ? message : message.substring(0, 200));
    }

    private static String repository(String target) {
        if (target == null) return null;
        Matcher matcher = REPOSITORY.matcher(target.trim());
        if (!matcher.find()) return null;
        return matcher.group(1) + "/" + matcher.group(2).replaceAll("\\.git$", "");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String encodePath(String value) {
        return java.util.Arrays.stream(value.split("/"))
                .map(GitHubRepositoryObservationVerifier::encode).reduce((a, b) -> a + "/" + b).orElse("");
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value.trim();
    }

    private record PullEvidence(String repository, int number) {}
}
