package com.metatron.workforce.observation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.management.RepositoryPullRequestAutonomousCapability;
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
import java.util.List;
import java.util.Optional;
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

    private final HttpClient http;
    private final ObjectMapper json;
    private final String apiBase;
    private final String token;

    /** Explicitly select the production constructor because a package-private test constructor also exists. */
    @Autowired
    public GitHubRepositoryObservationVerifier(ObjectMapper json) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build(), json,
                "https://api.github.com", env("GITHUB_TOKEN"));
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
            PullEvidence pull = pullEvidence(executionEvidenceReferences);
            if (pull != null) return Optional.of(observePull(requirement, executionEvidenceReferences, pull, at));
            return Optional.of(observeReadOnly(requirement, executionEvidenceReferences, repo, at));
        } catch (Exception failure) {
            return Optional.of(new ObservationReport(
                    "observation:github:" + requirement.requirementId() + ":failed",
                    requirement.requirementId(), requirement.objectiveId(), requirement.target(),
                    "GitHub verification failed", "authoritative-github-api-read", at, at,
                    List.of("observation-error:" + failure.getClass().getSimpleName()), 0.0,
                    ObservationReport.Quality.INSUFFICIENT,
                    String.valueOf(failure.getMessage()), ObservationReport.CriterionResult.INCONCLUSIVE));
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

    private PullEvidence pullEvidence(List<String> evidence) {
        for (String value : evidence) {
            Matcher matcher = PR_EVIDENCE.matcher(value == null ? "" : value.trim());
            if (matcher.matches()) return new PullEvidence(matcher.group(1), Integer.parseInt(matcher.group(2)));
        }
        return null;
    }

    private static String probeEvidence(List<String> evidence) {
        for (String value : evidence) {
            Matcher matcher = PROBE_EVIDENCE.matcher(value == null ? "" : value.trim());
            if (matcher.matches()) return matcher.group(1);
        }
        return "";
    }

    private JsonNode get(String path) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(apiBase + path))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "metatron-workforce-observation");
        if (!token.isBlank()) request.header("Authorization", "Bearer " + token);
        HttpResponse<String> response = http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("GitHub observation HTTP " + response.statusCode());
        return json.readTree(response.body());
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
