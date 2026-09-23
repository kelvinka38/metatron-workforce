package com.metatron.workforce.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production incident (2026-09-23, case build-and-deliver "Metatron Workforce Control Center"):
 * {@code RepositoryWorkspaceMaterializationService.ensureRepositoryExists()}, added for {@code
 * createIfMissing}, created the destination repository (HTTP 201) but its very next existence check --
 * either inside the same call on a later invocation, or the caller's own subsequent commit-resolution
 * read -- raced GitHub's own read-after-write replication and saw 404 for a repository that had just been
 * created. The step's bounded-local-retry (3 attempts) retried {@code ensureRepositoryExists}, which
 * itself re-ran the pre-create existence check, saw the same stale 404, and tried to create the repository
 * again -- which GitHub correctly rejected with 422 "name already exists on this account". All 3 attempts
 * were burned on this race within roughly 15 seconds, permanently blocking the Objective
 * (bounded-local-retry-exhausted) even though the repository genuinely existed and was fully readable
 * (proven hours later via the identical governed action).
 *
 * <p>These tests prove: (1) a create that is immediately followed by a transient 404 on read no longer
 * fails -- the service polls with a short bounded backoff until the repository is readable before
 * returning; (2) a 422 "already exists" response to the create call itself (the exact production failure)
 * is treated as the repository already existing, not a fatal error; (3) genuinely permanent unreadability
 * (GitHub never catches up) still fails closed with a typed exception instead of hanging forever.</p>
 */
final class RepositoryCreationPropagationLagTest {
    @TempDir Path temp;
    private FakeGitHubApiServer github;

    @AfterEach
    void tearDown() {
        if (github != null) github.stop();
    }

    @Test
    void repositoryBecomingReadableShortlyAfterCreationNoLongerFailsTheMaterialization() throws Exception {
        github = new FakeGitHubApiServer(404, 1, 201, "");
        RepositoryWorkspaceMaterializationService service = new RepositoryWorkspaceMaterializationService(
                HttpClient.newHttpClient(), "test-token", new ObjectiveWorkspaceService(temp),
                new ObjectMapper(), github.baseUri());

        // resolveCommit()'s own GET is a distinct, distinguishable failure -- reaching it proves
        // ensureRepositoryExists() itself returned normally despite the transient 404 after creation.
        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                service.materialize("worker", "objective-lag-1", "kelvinka38/fresh-app", "main", true));

        assertTrue(failure.getMessage().contains("repository commit resolution HTTP 404"),
                "materialization must proceed past repository creation once the repository becomes "
                        + "readable, instead of failing inside ensureRepositoryExists: " + failure.getMessage());
        assertEquals(1, github.createAttempts(), "the repository must be created exactly once");
        // The discriminating assertion: the previous implementation never re-checked repository
        // readability after a successful create at all, so it issued zero reads here. Proving at least
        // two post-create reads happened proves the new bounded-backoff poll actually ran (and absorbed
        // the injected 404) rather than merely happening to reach the same downstream failure by luck.
        assertTrue(github.postCreateReadsIssued() >= 2,
                "must have polled repository readability at least twice (one stale 404, one confirming 200) "
                        + "after creation, actual reads=" + github.postCreateReadsIssued());
    }

    @Test
    void aRacedAlreadyExistsResponseOnCreateIsTreatedAsTheRepositoryAlreadyExistingNotAFatalFailure() throws Exception {
        // The exact production 422 body reported for this incident.
        String alreadyExistsBody = "{\"message\":\"Repository creation failed.\",\"errors\":[{\"resource\":"
                + "\"Repository\",\"code\":\"custom\",\"field\":\"name\",\"message\":\"name already exists on this account\"}]}";
        github = new FakeGitHubApiServer(404, 0, 422, alreadyExistsBody);
        RepositoryWorkspaceMaterializationService service = new RepositoryWorkspaceMaterializationService(
                HttpClient.newHttpClient(), "test-token", new ObjectiveWorkspaceService(temp),
                new ObjectMapper(), github.baseUri());

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                service.materialize("worker", "objective-lag-2", "kelvinka38/fresh-app", "main", true));

        assertTrue(failure.getMessage().contains("repository commit resolution HTTP 404"),
                "a 422 'already exists' on create must be treated as the repository existing, not thrown "
                        + "as a fatal repository-creation failure: " + failure.getMessage());
    }

    @Test
    void repositoryThatNeverBecomesReadableStillFailsClosedInsteadOfHangingForever() throws Exception {
        github = new FakeGitHubApiServer(404, Integer.MAX_VALUE, 201, "");
        RepositoryWorkspaceMaterializationService service = new RepositoryWorkspaceMaterializationService(
                HttpClient.newHttpClient(), "test-token", new ObjectiveWorkspaceService(temp),
                new ObjectMapper(), github.baseUri());

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                service.materialize("worker", "objective-lag-3", "kelvinka38/fresh-app", "main", true));

        assertTrue(failure.getMessage().contains("not readable after creation"),
                "permanent unreadability must still fail closed with a typed message: " + failure.getMessage());
    }

    /**
     * Serves exactly the GitHub REST surface {@code ensureRepositoryExists}/{@code awaitRepositoryReadable}
     * call: {@code GET repos/{repo}} (404 for the first {@code notFoundReadsAfterCreate} reads issued
     * strictly after a successful/raced-exists create response, 200 thereafter; always 404 before create)
     * and {@code POST user/repos} (returns {@code createStatus}/{@code createBody} once).
     */
    private static final class FakeGitHubApiServer {
        private final HttpServer server;
        private final AtomicInteger createAttempts = new AtomicInteger();
        private final AtomicInteger readsSinceCreate = new AtomicInteger();
        private final AtomicInteger notFoundReadsAfterCreate;
        private final int createStatus;
        private final String createBody;
        private volatile boolean created = false;

        FakeGitHubApiServer(int initialGetStatus, int notFoundReadsAfterCreate, int createStatus, String createBody) throws IOException {
            this.notFoundReadsAfterCreate = new AtomicInteger(notFoundReadsAfterCreate);
            this.createStatus = createStatus;
            this.createBody = createBody;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String method = exchange.getRequestMethod();
                String path = exchange.getRequestURI().getPath();
                try {
                    if ("GET".equals(method) && path.startsWith("/repos/") && path.endsWith("/fresh-app")) {
                        handleExistenceCheck(exchange);
                    } else if ("POST".equals(method) && path.equals("/user/repos")) {
                        handleCreate(exchange);
                    } else if ("GET".equals(method) && path.contains("/commits/")) {
                        respond(exchange, 404, "");
                    } else {
                        respond(exchange, 500, "unexpected call: " + method + " " + path);
                    }
                } catch (IOException failure) {
                    respond(exchange, 500, "stub failure: " + failure);
                }
            });
            server.start();
        }

        private void handleExistenceCheck(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
            if (!created) {
                respond(exchange, 404, "");
                return;
            }
            int issued = readsSinceCreate.getAndIncrement();
            if (issued < notFoundReadsAfterCreate.get()) {
                respond(exchange, 404, "");
            } else {
                respond(exchange, 200, "{\"full_name\":\"kelvinka38/fresh-app\"}");
            }
        }

        private void handleCreate(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
            createAttempts.incrementAndGet();
            created = true;
            respond(exchange, createStatus, createBody);
        }

        private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }

        URI baseUri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        }

        int createAttempts() {
            return createAttempts.get();
        }

        int postCreateReadsIssued() {
            return readsSinceCreate.get();
        }

        void stop() {
            server.stop(0);
        }
    }
}
