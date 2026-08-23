package com.metatron.workforce.runtime;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Transport boundary for a runtime hosted outside the Workforce JVM.
 *
 * The client carries only execution attribution. Authorization remains an
 * upstream institutional concern and must already have been resolved.
 */
public final class RemoteRuntimeExecutor {
    private final HttpClient client;

    public RemoteRuntimeExecutor() {
        this(HttpClient.newHttpClient());
    }

    public RemoteRuntimeExecutor(HttpClient client) {
        this.client = Objects.requireNonNull(client);
    }

    public CompletableFuture<HttpResponse<String>> executeAsync(
            URI endpoint,
            RuntimeExecutionCommand command) {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(command, "command");

        String body = "{\"executionId\":\"" + escape(command.executionId())
                + "\",\"workerId\":\"" + escape(command.workerId())
                + "\",\"runtimeId\":\"" + escape(command.runtimeId()) + "\"}";

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
