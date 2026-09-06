package com.metatron.workforce.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Publishes verified committed Objective-workspace changes as a reviewable GitHub proposal without exposing
 * repository credentials to the Worker sandbox. Publication is deliberately proposal-only: there is
 * no merge action and canonical branch mutation is never performed.
 */
public final class GitHubWorkspaceProposalPublisher {
    public record Publication(
            String repository,
            String baseBranch,
            String sourceCommitSha,
            String localHeadSha,
            String branch,
            String remoteCommitSha,
            int pullRequestNumber,
            String pullRequestUrl,
            List<String> changedPaths) {
        public Publication {
            repository = require(repository, "repository");
            baseBranch = require(baseBranch, "baseBranch");
            sourceCommitSha = requireSha(sourceCommitSha, "sourceCommitSha");
            localHeadSha = requireSha(localHeadSha, "localHeadSha");
            branch = require(branch, "branch");
            remoteCommitSha = requireSha(remoteCommitSha, "remoteCommitSha");
            if (pullRequestNumber < 1) throw new IllegalArgumentException("pullRequestNumber must be positive");
            pullRequestUrl = require(pullRequestUrl, "pullRequestUrl");
            changedPaths = List.copyOf(Objects.requireNonNull(changedPaths, "changedPaths"));
            if (changedPaths.isEmpty()) throw new IllegalArgumentException("proposal requires at least one changed path");
        }
    }

    record Change(String status, String path) {
        Change {
            status = require(status, "status");
            path = require(path, "path");
            if (!Set.of("A", "M", "D").contains(status)) throw new IllegalArgumentException("unsupported change status: " + status);
            requireSafePath(path);
        }
    }

    private static final int MAX_CHANGED_PATHS = 50;
    private static final long MAX_FILE_BYTES = 2_000_000L;

    private final HttpClient http;
    private final String token;
    private final ObjectiveWorkspaceService workspaces;
    private final WorkerExecutionSandboxService sandbox;
    private final ObjectMapper json;
    private final URI apiBase;

    public GitHubWorkspaceProposalPublisher(HttpClient http,
                                            String token,
                                            ObjectiveWorkspaceService workspaces,
                                            WorkerExecutionSandboxService sandbox,
                                            ObjectMapper json) {
        this(http, token, workspaces, sandbox, json, URI.create("https://api.github.com/"));
    }

    GitHubWorkspaceProposalPublisher(HttpClient http,
                                     String token,
                                     ObjectiveWorkspaceService workspaces,
                                     WorkerExecutionSandboxService sandbox,
                                     ObjectMapper json,
                                     URI apiBase) {
        this.http = Objects.requireNonNull(http, "http");
        this.token = token == null ? "" : token.trim();
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
        this.json = Objects.requireNonNull(json, "json");
        this.apiBase = Objects.requireNonNull(apiBase, "apiBase");
    }

    public boolean provisioned() {
        return !token.isBlank();
    }

    public Publication publish(String workerId,
                               String objectiveId,
                               String requestedTitle,
                               String requestedBody) {
        if (!provisioned()) throw new IllegalStateException("github-proposal-token-not-provisioned");
        ObjectiveWorkspaceService.ObjectiveWorkspace workspace = workspaces.provision(objectiveId, workerId);
        Map<String, String> provenance = provenance(workspace);
        String repository = provenance.getOrDefault("repository", "");
        String sourceSha = requireSha(provenance.getOrDefault("commitSha", ""), "materialized source commit");
        String defaultBranch = get("repos/" + repository).path("default_branch").asText("").trim();
        if (defaultBranch.isBlank()) throw new IllegalStateException("GitHub repository default branch missing");

        JsonNode baseRef = get("repos/" + repository + "/git/ref/heads/" + encodePath(defaultBranch));
        String currentBaseSha = requireSha(baseRef.at("/object/sha").asText(""), "current base SHA");
        if (!sourceSha.equals(currentBaseSha)) {
            throw new IllegalStateException("proposal source is stale: materialized=" + sourceSha + " current=" + currentBaseSha);
        }

        String localHead = git(workerId, objectiveId, List.of("rev-parse", "HEAD")).trim();
        requireSha(localHead, "local HEAD");
        String roots = git(workerId, objectiveId, List.of("rev-list", "--max-parents=0", "HEAD")).trim();
        List<String> rootCommits = roots.lines().map(String::trim).filter(v -> !v.isBlank()).toList();
        if (rootCommits.size() != 1) throw new IllegalStateException("Objective workspace must have exactly one local baseline root");
        String localBaseline = requireSha(rootCommits.getFirst(), "local baseline");
        if (localHead.equals(localBaseline)) throw new IllegalStateException("proposal requires a committed work-product delta");

        String status = git(workerId, objectiveId, List.of("status", "--porcelain")).trim();
        if (!status.isBlank()) throw new IllegalStateException("proposal requires a clean committed Objective workspace");

        String diff = git(workerId, objectiveId,
                List.of("diff", "--name-status", "--no-renames", localBaseline + ".." + localHead, "--"));
        List<Change> changes = parseChanges(diff);
        if (changes.isEmpty()) throw new IllegalStateException("proposal has no changed paths");
        if (changes.size() > MAX_CHANGED_PATHS) throw new IllegalStateException("proposal exceeds changed-path budget");

        String branch = branchName(objectiveId, localHead);
        JsonNode existingRef = getOrNull("repos/" + repository + "/git/ref/heads/" + encodePath(branch));
        String remoteCommit;
        if (existingRef != null) {
            remoteCommit = requireSha(existingRef.at("/object/sha").asText(""), "existing proposal branch SHA");
            JsonNode existingCommit = get("repos/" + repository + "/git/commits/" + remoteCommit);
            String parent = existingCommit.path("parents").isArray() && !existingCommit.path("parents").isEmpty()
                    ? existingCommit.path("parents").get(0).path("sha").asText("") : "";
            if (!sourceSha.equals(parent)) throw new SecurityException("existing proposal branch is not source-bound");
        } else {
            JsonNode sourceCommit = get("repos/" + repository + "/git/commits/" + sourceSha);
            String baseTree = requireSha(sourceCommit.at("/tree/sha").asText(""), "base tree SHA");
            ArrayNode treeEntries = json.createArrayNode();
            for (Change change : changes) {
                ObjectNode entry = treeEntries.addObject();
                entry.put("path", change.path());
                if ("D".equals(change.status())) {
                    entry.put("mode", "100644");
                    entry.put("type", "blob");
                    entry.putNull("sha");
                    continue;
                }
                Path file = workspaces.resolve(workspace, change.path());
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
                    throw new SecurityException("proposal path is not a regular workspace file: " + change.path());
                }
                byte[] bytes;
                try {
                    bytes = Files.readAllBytes(file);
                } catch (java.io.IOException failure) {
                    throw new IllegalStateException("cannot read proposal path: " + change.path(), failure);
                }
                if (bytes.length > MAX_FILE_BYTES) throw new IllegalStateException("proposal file exceeds byte budget: " + change.path());
                ObjectNode blob = json.createObjectNode();
                blob.put("content", Base64.getEncoder().encodeToString(bytes));
                blob.put("encoding", "base64");
                String blobSha = requireSha(post("repos/" + repository + "/git/blobs", blob, 201).path("sha").asText(""), "blob SHA");
                entry.put("mode", Files.isExecutable(file) ? "100755" : "100644");
                entry.put("type", "blob");
                entry.put("sha", blobSha);
            }

            ObjectNode treeBody = json.createObjectNode();
            treeBody.put("base_tree", baseTree);
            treeBody.set("tree", treeEntries);
            String treeSha = requireSha(post("repos/" + repository + "/git/trees", treeBody, 201).path("sha").asText(""), "proposal tree SHA");

            ObjectNode commitBody = json.createObjectNode();
            commitBody.put("message", "metatron: publish governed Objective " + shortHash(objectiveId));
            commitBody.put("tree", treeSha);
            commitBody.putArray("parents").add(sourceSha);
            remoteCommit = requireSha(post("repos/" + repository + "/git/commits", commitBody, 201).path("sha").asText(""), "proposal commit SHA");

            ObjectNode refBody = json.createObjectNode();
            refBody.put("ref", "refs/heads/" + branch);
            refBody.put("sha", remoteCommit);
            post("repos/" + repository + "/git/refs", refBody, 201);
        }

        JsonNode pull = findOpenPull(repository, defaultBranch, branch);
        if (pull == null) {
            ObjectNode pullBody = json.createObjectNode();
            pullBody.put("title", normalizeTitle(requestedTitle, objectiveId));
            pullBody.put("head", branch);
            pullBody.put("base", defaultBranch);
            pullBody.put("body", normalizeBody(requestedBody, objectiveId, localHead));
            pull = post("repos/" + repository + "/pulls", pullBody, 201);
        }
        int number = pull.path("number").asInt(0);
        String url = pull.path("html_url").asText("").trim();
        if (number < 1 || url.isBlank()) throw new IllegalStateException("GitHub pull request response incomplete");
        if (pull.path("merged").asBoolean(false)) throw new SecurityException("governed proposal unexpectedly merged");

        return new Publication(repository, defaultBranch, sourceSha, localHead, branch, remoteCommit,
                number, url, changes.stream().map(Change::path).toList());
    }

    static List<Change> parseChanges(String diff) {
        List<Change> changes = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (diff == null || diff.isBlank()) return List.of();
        for (String line : diff.lines().toList()) {
            if (line.isBlank()) continue;
            int tab = line.indexOf('\t');
            if (tab != 1) throw new IllegalStateException("unsupported Git change record: " + line);
            Change change = new Change(line.substring(0, 1), line.substring(tab + 1));
            if (!seen.add(change.path())) throw new IllegalStateException("duplicate changed path: " + change.path());
            changes.add(change);
        }
        return List.copyOf(changes);
    }

    static String branchName(String objectiveId, String localHead) {
        return "metatron/objective-" + shortHash(require(objectiveId, "objectiveId")) + "-"
                + requireSha(localHead, "localHead").substring(0, 8);
    }

    private Map<String, String> provenance(ObjectiveWorkspaceService.ObjectiveWorkspace workspace) {
        String body = workspaces.read(workspace, ".metatron-repository");
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : body.lines().toList()) {
            int split = line.indexOf('=');
            if (split > 0) fields.put(line.substring(0, split).trim(), line.substring(split + 1).trim());
        }
        String repository = fields.getOrDefault("repository", "");
        if (!repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            throw new IllegalStateException("invalid materialized repository provenance");
        }
        return Map.copyOf(fields);
    }

    private JsonNode findOpenPull(String repository, String base, String branch) {
        String owner = repository.substring(0, repository.indexOf('/'));
        JsonNode pulls = get("repos/" + repository + "/pulls?state=open&base=" + encode(base)
                + "&head=" + encode(owner + ":" + branch));
        return pulls.isArray() && !pulls.isEmpty() ? pulls.get(0) : null;
    }

    private String git(String workerId, String objectiveId, List<String> args) {
        WorkerExecutionSandboxService.SandboxResult result = sandbox.run(workerId, objectiveId, "git", args);
        if (!result.success()) throw new IllegalStateException("governed Git inspection failed: " + abbreviate(result.output()));
        return result.output();
    }

    private JsonNode get(String relative) {
        JsonNode result = getOrNull(relative);
        if (result == null) throw new IllegalStateException("GitHub GET not found: " + relative);
        return result;
    }

    private JsonNode getOrNull(String relative) {
        HttpResponse<String> response = send("GET", relative, null);
        if (response.statusCode() == 404) return null;
        if (response.statusCode() != 200) throw failure("GET " + relative, response);
        try {
            return json.readTree(response.body());
        } catch (Exception invalid) {
            throw new IllegalStateException("invalid GitHub JSON", invalid);
        }
    }

    private JsonNode post(String relative, JsonNode body, int expected) {
        HttpResponse<String> response = send("POST", relative, body == null ? null : body.toString());
        if (response.statusCode() != expected) throw failure("POST " + relative, response);
        try {
            return json.readTree(response.body());
        } catch (Exception invalid) {
            throw new IllegalStateException("invalid GitHub JSON", invalid);
        }
    }

    private HttpResponse<String> send(String method, String relative, String body) {
        try {
            URI uri = apiBase.resolve(relative);
            HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/vnd.github+json")
                    .header("Authorization", "Bearer " + token)
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("User-Agent", "metatron-workforce-general-proposal");
            if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
            else request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GitHub proposal request interrupted", interrupted);
        } catch (Exception failure) {
            throw new IllegalStateException("GitHub proposal request failed", failure);
        }
    }

    private static IllegalStateException failure(String operation, HttpResponse<String> response) {
        return new IllegalStateException(operation + " HTTP " + response.statusCode() + " " + abbreviate(response.body()));
    }

    private static void requireSafePath(String path) {
        String value = require(path, "path");
        if (value.startsWith("/") || value.contains("\\") || value.contains("..") || value.contains("\n") || value.contains("\r")
                || value.contains("\t") || value.equals(".git") || value.startsWith(".git/")
                || value.equals(".metatron-workspace") || value.equals(".metatron-repository")) {
            throw new SecurityException("unsafe proposal path: " + value);
        }
    }

    private static String normalizeTitle(String title, String objectiveId) {
        String value = title == null ? "" : title.trim();
        if (value.isBlank()) value = "metatron: governed Objective " + shortHash(objectiveId);
        return value.length() <= 240 ? value : value.substring(0, 240);
    }

    private static String normalizeBody(String body, String objectiveId, String localHead) {
        String value = body == null ? "" : body.trim();
        String guard = "\n\nGenerated by a governed Metatron Cognitive Worker from Objective " + shortHash(objectiveId)
                + ". Local committed work product: " + localHead + ". Human review is required; this action cannot merge.";
        if (value.isBlank()) value = "Review the committed Objective work product.";
        value += guard;
        return value.length() <= 8_000 ? value : value.substring(0, 8_000);
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    require(value, "hash input").getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (Exception failure) {
            throw new IllegalStateException("cannot hash proposal identity", failure);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String encodePath(String value) {
        return java.util.Arrays.stream(value.split("/")).map(GitHubWorkspaceProposalPublisher::encode)
                .reduce((a, b) -> a + "/" + b).orElse("");
    }

    private static String requireSha(String value, String field) {
        String out = require(value, field).toLowerCase(java.util.Locale.ROOT);
        if (!out.matches("[0-9a-f]{40}")) throw new IllegalArgumentException(field + " must be a Git SHA");
        return out;
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
        return value.trim();
    }

    private static String abbreviate(String value) {
        String clean = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() <= 500 ? clean : clean.substring(0, 500);
    }
}
