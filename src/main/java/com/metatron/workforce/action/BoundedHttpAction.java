package com.metatron.workforce.action;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A destination-bound HTTP Action adapter for real web/API tools.
 *
 * <p>The Worker may choose the path/body, but cannot choose the scheme, host, port, method,
 * credential, consequence, worker allow-list, authorization allow-list or admitted path prefix.
 * Credentials are injected inside the adapter and are never included in observations/evidence.</p>
 */
public final class BoundedHttpAction implements ActionFabric.Action {
    private static final int MAX_RESPONSE_BYTES = 512_000;

    private final String actionRef;
    private final ActionFabric.Consequence consequence;
    private final Set<String> allowedWorkers;
    private final Set<String> acceptedAuthorizations;
    private final HttpClient http;
    private final URI destinationBase;
    private final String method;
    private final String admittedPathPrefix;
    private final Map<String, String> privateHeaders;
    private final String accept;

    public BoundedHttpAction(String actionRef,
                             ActionFabric.Consequence consequence,
                             Set<String> allowedWorkers,
                             Set<String> acceptedAuthorizations,
                             HttpClient http,
                             String destinationBase,
                             String method,
                             String admittedPathPrefix,
                             Map<String, String> privateHeaders,
                             String accept) {
        this.actionRef = require(actionRef, "actionRef");
        this.consequence = Objects.requireNonNull(consequence, "consequence");
        this.allowedWorkers = Set.copyOf(Objects.requireNonNull(allowedWorkers, "allowedWorkers"));
        this.acceptedAuthorizations = Set.copyOf(Objects.requireNonNull(acceptedAuthorizations, "acceptedAuthorizations"));
        if (this.allowedWorkers.isEmpty()) throw new IllegalArgumentException("allowedWorkers required");
        if (this.acceptedAuthorizations.isEmpty()) throw new IllegalArgumentException("acceptedAuthorizations required");
        this.http = Objects.requireNonNull(http, "http");
        this.destinationBase = URI.create(require(destinationBase, "destinationBase").replaceAll("/+$", ""));
        if (!"https".equalsIgnoreCase(this.destinationBase.getScheme()) && !loopback(this.destinationBase)) {
            throw new IllegalArgumentException("bounded HTTP destination must use https (except loopback tests/runtime)");
        }
        this.method = require(method, "method").toUpperCase();
        if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(this.method)) {
            throw new IllegalArgumentException("unsupported bounded HTTP method: " + method);
        }
        this.admittedPathPrefix = normalizePrefix(admittedPathPrefix);
        this.privateHeaders = privateHeaders == null ? Map.of() : Map.copyOf(privateHeaders);
        this.accept = accept == null || accept.isBlank() ? "application/json" : accept.trim();
        if (this.consequence == ActionFabric.Consequence.READ_ONLY && !"GET".equals(this.method)) {
            throw new IllegalArgumentException("read-only bounded HTTP action must use GET");
        }
    }

    @Override public String actionRef() { return actionRef; }
    @Override public ActionFabric.Consequence consequence() { return consequence; }
    @Override public Set<String> allowedWorkers() { return allowedWorkers; }
    @Override public Set<String> acceptedAuthorizations() { return acceptedAuthorizations; }

    @Override
    public ActionFabric.ActionObservation invoke(ActionFabric.ActionRequest request) {
        String path = request.inputs().getOrDefault("path", admittedPathPrefix);
        if (path == null || path.isBlank()) path = "/";
        if (!path.startsWith("/")) path = "/" + path;
        if (!pathOnly(path).startsWith(admittedPathPrefix)) {
            throw new SecurityException("http-action-path-outside-admitted-prefix:" + pathOnly(path));
        }
        URI target = destinationBase.resolve(path);
        if (!sameDestination(destinationBase, target)) {
            throw new SecurityException("http-action-destination-escape");
        }
        String body = request.inputs().getOrDefault("body", "");
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", accept)
                    .header("User-Agent", "metatron-workforce-action-fabric");
            for (Map.Entry<String, String> header : privateHeaders.entrySet()) {
                if (header.getValue() != null && !header.getValue().isBlank()) {
                    builder.header(header.getKey(), header.getValue());
                }
            }
            if ("GET".equals(method)) {
                builder.GET();
            } else {
                builder.header("Content-Type", request.inputs().getOrDefault("contentType", "application/json"));
                builder.method(method, HttpRequest.BodyPublishers.ofString(body));
            }
            HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            byte[] raw = response.body() == null ? new byte[0] : response.body();
            if (raw.length > MAX_RESPONSE_BYTES) {
                return ActionFabric.ActionObservation.failure(actionRef,
                        "bounded API response exceeded " + MAX_RESPONSE_BYTES + " bytes",
                        List.of(provenance(target, response.statusCode()) + ":oversized=true"));
            }
            String responseBody = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
            boolean success = response.statusCode() >= 200 && response.statusCode() < 300;
            Map<String, String> outputs = new LinkedHashMap<>();
            outputs.put("httpStatus", Integer.toString(response.statusCode()));
            outputs.put("responseBody", responseBody);
            outputs.put("requestPath", path);
            return new ActionFabric.ActionObservation(actionRef, success,
                    method + " " + target.getHost() + pathOnly(path) + " -> HTTP " + response.statusCode(),
                    outputs, List.of(provenance(target, response.statusCode())), java.time.Instant.now());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("bounded HTTP action interrupted", interrupted);
        } catch (Exception failure) {
            throw new IllegalStateException("bounded HTTP action failed: " + failure.getMessage(), failure);
        }
    }

    private String provenance(URI target, int status) {
        return "bounded-http:action=" + actionRef + ":method=" + method
                + ":destination=" + target.getScheme() + "://" + target.getHost()
                + ":path=" + target.getPath() + ":status=" + status
                + ":credential=" + (privateHeaders.isEmpty() ? "none" : "isolated");
    }

    private static String normalizePrefix(String prefix) {
        String value = prefix == null || prefix.isBlank() ? "/" : prefix.trim();
        if (!value.startsWith("/")) value = "/" + value;
        if (value.contains("..")) throw new IllegalArgumentException("admittedPathPrefix cannot contain ..");
        return value;
    }

    private static String pathOnly(String path) {
        int query = path.indexOf('?');
        return query >= 0 ? path.substring(0, query) : path;
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

    private static boolean loopback(URI uri) {
        String host = uri.getHost();
        return "http".equalsIgnoreCase(uri.getScheme())
                && ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host));
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
        return value.trim();
    }
}
