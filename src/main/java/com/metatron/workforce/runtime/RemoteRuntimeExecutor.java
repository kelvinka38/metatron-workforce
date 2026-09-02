package com.metatron.workforce.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Transport boundary for a runtime hosted outside the Workforce JVM.
 *
 * <p>The transport carries the fully governed execution command. Institutional authorization remains
 * upstream; a transport credential only authenticates the runtime-to-runtime hop and never substitutes
 * for Assignment/Authorization/Dispatch bindings inside the command.</p>
 */
public final class RemoteRuntimeExecutor {
    public static final String EXECUTION_TOKEN_HEADER = "X-Metatron-Runtime-Execution-Token";

    private final HttpClient client;
    private final ObjectMapper objectMapper;
    private final String executionToken;

    public RemoteRuntimeExecutor() {
        this(HttpClient.newHttpClient(), new ObjectMapper(), env("METATRON_RUNTIME_EXECUTION_TOKEN"));
    }

    public RemoteRuntimeExecutor(HttpClient client) {
        this(client, new ObjectMapper(), env("METATRON_RUNTIME_EXECUTION_TOKEN"));
    }

    public RemoteRuntimeExecutor(HttpClient client, String executionToken) {
        this(client, new ObjectMapper(), executionToken);
    }

    RemoteRuntimeExecutor(HttpClient client, ObjectMapper objectMapper) {
        this(client, objectMapper, "");
    }

    RemoteRuntimeExecutor(HttpClient client, ObjectMapper objectMapper, String executionToken) {
        this.client = Objects.requireNonNull(client);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.executionToken = executionToken == null ? "" : executionToken.trim();
    }

    public CompletableFuture<HttpResponse<String>> executeAsync(
            URI endpoint,
            RuntimeExecutionCommand command) {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(command, "command");

        final String body;
        try {
            body = objectMapper.writeValueAsString(command);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("runtime execution command is not serializable", failure);
        }

        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json");
        if (!executionToken.isBlank()) request.header(EXECUTION_TOKEN_HEADER, executionToken);

        return client.sendAsync(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value.trim();
    }
}
