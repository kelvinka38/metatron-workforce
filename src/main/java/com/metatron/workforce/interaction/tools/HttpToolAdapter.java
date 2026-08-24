package com.metatron.workforce.interaction.tools;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/** Generic read-only HTTP capability. Write capabilities must use dedicated adapters and policy. */
public final class HttpToolAdapter implements ToolAdapter {
    private final HttpClient client;
    private final Duration timeout;

    public HttpToolAdapter(HttpClient client, Duration timeout) {
        this.client = Objects.requireNonNull(client, "client");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    @Override
    public String capability() {
        return "http.read";
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            URI uri = URI.create(request.target());
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme()))
                return ToolResult.failure("unsupported_scheme");
            HttpRequest httpRequest = HttpRequest.newBuilder(uri).timeout(timeout).GET().build();
            HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                return ToolResult.failure("http_status:" + response.statusCode());
            return ToolResult.success(response.body());
        } catch (Exception e) {
            return ToolResult.failure("request_failed");
        }
    }
}
