package com.metatron.workforce.interaction.tools;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/** Read-only Hetzner Cloud API adapter. Token is supplied through runtime configuration. */
public final class HetznerReadAdapter implements ToolAdapter {
    private final HttpClient client;
    private final Duration timeout;
    private final String token;

    public HetznerReadAdapter(HttpClient client, Duration timeout, String token) {
        this.client = Objects.requireNonNull(client, "client");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.token = Objects.requireNonNullElse(token, "").trim();
    }

    @Override public String capability() { return "hetzner.read"; }

    @Override public ToolResult execute(ToolRequest request) {
        Objects.requireNonNull(request, "request");
        if (token.isEmpty()) return ToolResult.failure(request, "hetzner_token_missing");
        String path = request.target().trim();
        if (!path.startsWith("/servers") && !path.startsWith("/server_types") && !path.startsWith("/datacenters") && !path.startsWith("/locations"))
            return ToolResult.failure(request, "invalid_hetzner_path");
        if (path.contains("..") || path.contains("//")) return ToolResult.failure(request, "invalid_hetzner_path");
        try {
            URI uri = URI.create("https://api.hetzner.cloud/v1" + path);
            HttpRequest httpRequest = HttpRequest.newBuilder(uri).timeout(timeout)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json").GET().build();
            HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) return ToolResult.failure(request, "http_status:" + response.statusCode());
            return ToolResult.success(request, response.body());
        } catch (Exception e) { return ToolResult.failure(request, "hetzner_request_failed"); }
    }
}
