package com.metatron.workforce.workers.audit;

import com.metatron.workforce.workers.Worker;
import com.metatron.workforce.workers.WorkerContext;
import com.metatron.workforce.workers.WorkerResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Repository audit worker backed by real GitHub repository reads.
 *
 * A PASS is emitted only after GitHub returns repository metadata and a concrete
 * default-branch commit SHA. When GITHUB_TOKEN is configured it is used for
 * authenticated reads, including private repositories. Network/API failures are
 * surfaced as FAILED and are never converted into evidence.
 */
public final class RepositoryAuditWorker implements Worker {
    private static final Pattern REPOSITORY = Pattern.compile("(?i)(?:https?://github\\.com/)?([a-z0-9_.-]+)/([a-z0-9_.-]+)");
    private static final Pattern DEFAULT_BRANCH = Pattern.compile("\\\"default_branch\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern SHA = Pattern.compile("\\\"sha\\\"\\s*:\\s*\\\"([0-9a-f]{40})\\\"");

    private final HttpClient http;
    private final String apiBase;
    private final String githubToken;

    public RepositoryAuditWorker() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                "https://api.github.com", env("GITHUB_TOKEN"));
    }

    RepositoryAuditWorker(HttpClient http, String apiBase) {
        this(http, apiBase, "");
    }

    RepositoryAuditWorker(HttpClient http, String apiBase, String githubToken) {
        this.http = http;
        this.apiBase = apiBase.replaceAll("/+$", "");
        this.githubToken = githubToken == null ? "" : githubToken.trim();
    }

    @Override
    public WorkerResult execute(WorkerContext context) {
        Instant completedAt = Instant.now();
        String target = extractRepository(context.objective());
        if (target == null) {
            return failed(context, "repository target missing; objective must contain owner/repo or github.com/owner/repo", completedAt);
        }

        try {
            HttpResponse<String> repository = get("/repos/" + target);
            if (repository.statusCode() != 200) {
                return failed(context, "repository metadata HTTP " + repository.statusCode() + " target=" + target, completedAt);
            }
            String defaultBranch = capture(DEFAULT_BRANCH, repository.body());
            if (defaultBranch == null) {
                return failed(context, "GitHub response missing default_branch target=" + target, completedAt);
            }

            HttpResponse<String> commit = get("/repos/" + target + "/commits/" + defaultBranch);
            if (commit.statusCode() != 200) {
                return failed(context, "default branch commit HTTP " + commit.statusCode() + " target=" + target + " branch=" + defaultBranch, completedAt);
            }
            String commitSha = capture(SHA, commit.body());
            if (commitSha == null) {
                return failed(context, "GitHub response missing commit SHA target=" + target, completedAt);
            }

            String evidence = """
                    Repository Audit Evidence
                    task=%s
                    objective=%s
                    source=github-api
                    authenticated=%s
                    repository=%s
                    defaultBranch=%s
                    commitSha=%s
                    metadataHttpStatus=%d
                    commitHttpStatus=%d
                    observedAt=%s
                    verdict=PASS
                    """.formatted(context.taskId(), context.objective(), !githubToken.isBlank(), target, defaultBranch,
                    commitSha, repository.statusCode(), commit.statusCode(), completedAt);
            return new WorkerResult("RepositoryAuditWorker", "PASS", evidence, completedAt);
        } catch (Exception failure) {
            return failed(context, "GitHub read failed: " + failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage()), completedAt);
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(apiBase + path))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "metatron-workforce-repository-audit");
        if (!githubToken.isBlank()) builder.header("Authorization", "Bearer " + githubToken);
        return http.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String extractRepository(String objective) {
        if (objective == null) return null;
        Matcher matcher = REPOSITORY.matcher(objective.trim());
        if (!matcher.find()) return null;
        String owner = matcher.group(1);
        String repo = matcher.group(2).replaceAll("\\.git$", "");
        if (owner.equalsIgnoreCase("http") || owner.equalsIgnoreCase("https")) return null;
        return owner + "/" + repo;
    }

    private static String capture(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value == null ? "" : value);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static WorkerResult failed(WorkerContext context, String reason, Instant completedAt) {
        String evidence = """
                Repository Audit Evidence
                task=%s
                objective=%s
                observedAt=%s
                verdict=FAILED
                reason=%s
                """.formatted(context.taskId(), context.objective(), completedAt, reason);
        return new WorkerResult("RepositoryAuditWorker", "FAILED", evidence, completedAt);
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value.trim();
    }
}
