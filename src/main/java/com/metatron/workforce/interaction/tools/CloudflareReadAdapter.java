package com.metatron.workforce.interaction.tools;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/** Read-only Cloudflare API adapter. Token is supplied through runtime configuration. */
public final class CloudflareReadAdapter implements ToolAdapter {
    private final HttpClient client;
    private final Duration timeout;
    private final String token;
    private final String accountId;

    public CloudflareReadAdapter(HttpClient client, Duration timeout, String token, String accountId) {
        this.client = Objects.requireNonNull(client, "client");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.token = Objects.requireNonNullElse(token, "").trim();
        this.accountId = Objects.requireNonNullElse(accountId, "").trim();
    }

    @Override public String capability() { return "cloudflare.read"; }

    @Override public ToolResult execute(ToolRequest request) {
        Objects.requireNonNull(request, "request");
        if (token.isEmpty()) return ToolResult.failure("cloudflare_token_missing");
        String path = request.target().trim();
        if (!path.startsWith("/accounts/") && !path.startsWith("/zones/")) return ToolResult.failure("invalid_cloudflare_path");
        if (path.contains("..") || path.contains("//")) return ToolResult.failure("invalid_cloudflare_path");
        if (path.startsWith("/accounts/") && !accountId.isEmpty() && !path.startsWith("/accounts/" + accountId + "/")) return ToolResult.failure("account_scope_denied");
        try {
            URI uri = URI.create("https://api.cloudflare.com/client/v4" + path);
            HttpRequest httpRequest = HttpRequest.newBuilder(uri).timeout(timeout)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json").GET().build();
            HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) return ToolResult.failure("http_status:" + response.statusCode());
            return ToolResult.success(response.body());
        } catch (Exception e) { return ToolResult.failure("cloudflare_request_failed"); }
    }
}
