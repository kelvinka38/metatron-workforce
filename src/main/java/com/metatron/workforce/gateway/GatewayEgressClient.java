package com.metatron.workforce.gateway;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Governed read-only Gateway egress for one explicitly configured destination.
 * The requesting capability supplies an admitted authorization reference; the
 * credential remains isolated inside this boundary client and is never returned.
 */
public final class GatewayEgressClient {
    private final HttpClient http;
    private final URI destinationBase;
    private final String bearerToken;
    private final String authorizationReference;

    public GatewayEgressClient(HttpClient http, String destinationBase, String bearerToken, String authorizationReference) {
        this.http = Objects.requireNonNull(http, "http");
        this.destinationBase = URI.create(Objects.requireNonNull(destinationBase, "destinationBase").replaceAll("/+$", ""));
        this.bearerToken = bearerToken == null ? "" : bearerToken.trim();
        this.authorizationReference = authorizationReference == null ? "" : authorizationReference.trim();
        if (!"https".equalsIgnoreCase(this.destinationBase.getScheme())
                && !isLoopbackTestDestination(this.destinationBase)) {
            throw new IllegalArgumentException("gateway egress destination must use https");
        }
    }

    public EgressResponse get(String pathAndQuery, String accept) throws Exception {
        if (authorizationReference.isBlank()) {
            return EgressResponse.denied("authorization_missing");
        }
        String requestId = UUID.randomUUID().toString();
        URI target = destinationBase.resolve(pathAndQuery.startsWith("/") ? pathAndQuery : "/" + pathAndQuery);
        if (!sameDestination(destinationBase, target)) {
            return EgressResponse.denied("destination_denied");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(15))
                .header("Accept", accept)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "metatron-gateway-egress");
        if (!bearerToken.isBlank()) builder.header("Authorization", "Bearer " + bearerToken);
        Instant observedAt = Instant.now();
        HttpResponse<String> response = http.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
        String provenance = "gateway-egress:" + requestId
                + ":destination=" + target.getScheme() + "://" + target.getHost()
                + ":authorization=" + authorizationReference
                + ":credential=" + (!bearerToken.isBlank() ? "isolated" : "none")
                + ":observedAt=" + observedAt;
        return new EgressResponse(response.statusCode(), response.body(), provenance, false, "");
    }

    private static boolean sameDestination(URI base, URI target) {
        return Objects.equals(base.getScheme(), target.getScheme())
                && Objects.equals(base.getHost(), target.getHost())
                && effectivePort(base) == effectivePort(target);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static boolean isLoopbackTestDestination(URI uri) {
        String host = uri.getHost();
        return "http".equalsIgnoreCase(uri.getScheme())
                && ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host));
    }

    public record EgressResponse(int statusCode, String body, String provenance, boolean denied, String denialReason) {
        static EgressResponse denied(String reason) {
            return new EgressResponse(0, "", "", true, reason);
        }
    }
}
