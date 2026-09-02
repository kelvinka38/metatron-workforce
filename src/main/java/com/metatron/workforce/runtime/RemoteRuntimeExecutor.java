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
 * The transport carries execution attribution plus the actual executable work specification.
 * Authorization remains an upstream institutional concern and must already have been resolved.
 */
public final class RemoteRuntimeExecutor {
    private final HttpClient client;
    private final ObjectMapper objectMapper;

    public RemoteRuntimeExecutor() {
        this(HttpClient.newHttpClient(), new ObjectMapper());
    }

    public RemoteRuntimeExecutor(HttpClient client) {
        this(client, new ObjectMapper());
    }

    RemoteRuntimeExecutor(HttpClient client, ObjectMapper objectMapper) {
        this.client = Objects.requireNonNull(client);
        this.objectMapper = Objects.requireNonNull(objectMapper);
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

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }
}
