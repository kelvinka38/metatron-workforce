package com.metatron.workforce.execution;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Read-only gateway audit capability. It never mutates the target. */
public final class GatewayAuditCapability implements ExecutionCapability {
    private final URI auditUri;
    private final String bearerToken;
    private final HttpClient client;

    public GatewayAuditCapability(String auditUrl, String bearerToken) {
        if (auditUrl == null || auditUrl.isBlank()) {
            throw new IllegalStateException("METATRON_GATEWAY_AUDIT_URL_MISSING");
        }
        this.auditUri = URI.create(auditUrl.trim());
        if (!"https".equalsIgnoreCase(auditUri.getScheme()) && !"http".equalsIgnoreCase(auditUri.getScheme())) {
            throw new IllegalStateException("METATRON_GATEWAY_AUDIT_URL_INVALID_SCHEME");
        }
        this.bearerToken = bearerToken == null ? "" : bearerToken.trim();
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public ExecutionResult execute(ExecutionCommand command) {
        Objects.requireNonNull(command, "command");
        Instant completed = Instant.now();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(auditUri)
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .header("Accept", "application/json,text/plain;q=0.9,*/*;q=0.8");
            if (!bearerToken.isBlank()) {
                builder.header("Authorization", "Bearer " + bearerToken);
            }
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String body = response.body() == null ? "" : response.body();
            if (body.length() > 4000) body = body.substring(0, 4000) + "...[truncated]";
            boolean success = response.statusCode() >= 200 && response.statusCode() < 300;
            String summary = "Gateway audit HTTP " + response.statusCode() + (success ? " PASS" : " FAIL");
            String evidence = "url=" + auditUri + ";status=" + response.statusCode() + ";body=" + body.replace("\n", "\\n");
            String message = ";capability=gateway.audit.read;summary=" + summary + ";evidence=" + evidence;
            return new ExecutionResult(command.executionId(), success ? ExecutionState.COMPLETED : ExecutionState.FAILED, message, completed);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new ExecutionResult(command.executionId(), ExecutionState.FAILED,
                    ";capability=gateway.audit.read;summary=Gateway audit interrupted;evidence=" + interrupted, completed);
        } catch (IOException | RuntimeException failure) {
            return new ExecutionResult(command.executionId(), ExecutionState.FAILED,
                    ";capability=gateway.audit.read;summary=Gateway audit failed;evidence=" + failure, completed);
        }
    }
}
