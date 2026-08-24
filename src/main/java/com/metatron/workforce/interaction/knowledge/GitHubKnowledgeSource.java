package com.metatron.workforce.interaction.knowledge;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Read-only GitHub REST knowledge source. Credentials are optional and supplied by configuration. */
public final class GitHubKnowledgeSource implements KnowledgeSource {
    private final HttpClient client;
    private final Duration timeout;
    private final String token;

    public GitHubKnowledgeSource(HttpClient client, Duration timeout, String token) {
        this.client = Objects.requireNonNull(client, "client");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.token = token == null ? "" : token.trim();
    }

    @Override
    public String sourceId() { return "github.rest"; }

    @Override
    public KnowledgeDocument retrieve(KnowledgeQuery query) {
        Objects.requireNonNull(query, "query");
        if (query.query().isBlank()) return null;
        String path = query.query().trim();
        if (!path.startsWith("/repos/") || path.contains("..")) return null;
        try {
            URI uri = URI.create("https://api.github.com" + path);
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .GET();
            if (!token.isEmpty()) builder.header("Authorization", "Bearer " + token);
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) return null;
            String documentId = sourceId() + ":" + uri;
            return new KnowledgeDocument(
                    documentId,
                    sourceId(),
                    path,
                    response.body(),
                    List.of(uri.toString()));
        } catch (Exception e) {
            return null;
        }
    }
}
