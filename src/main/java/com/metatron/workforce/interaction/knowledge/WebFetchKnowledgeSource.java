package com.metatron.workforce.interaction.knowledge;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Minimal HTTP knowledge source. Policy/allow-listing belongs at the caller boundary. */
public final class WebFetchKnowledgeSource implements KnowledgeSource {
    private final HttpClient client;
    private final Duration timeout;

    public WebFetchKnowledgeSource(HttpClient client, Duration timeout) {
        this.client = Objects.requireNonNull(client, "client");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    @Override
    public String sourceId() {
        return "web.fetch";
    }

    @Override
    public KnowledgeDocument retrieve(KnowledgeQuery query) {
        Objects.requireNonNull(query, "query");
        if (query.query().isBlank()) return null;
        URI uri;
        try {
            uri = URI.create(query.query().trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) return null;
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) return null;
            String documentId = sourceId() + ":" + uri;
            return new KnowledgeDocument(
                    documentId,
                    sourceId(),
                    uri.toString(),
                    response.body(),
                    List.of(uri.toString()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
