package com.metatron.workforce.workers.audit;

import com.metatron.workforce.workers.WorkerContext;
import com.metatron.workforce.workers.WorkerResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class RepositoryAuditWorkerTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void passRequiresRepositoryMetadataAndConcreteCommitSha() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/repos/kelvinka38/bios", exchange -> respond(exchange, 200, "{\"default_branch\":\"main\"}"));
        server.createContext("/repos/kelvinka38/bios/commits/main", exchange -> respond(exchange, 200,
                "{\"sha\":\"0123456789abcdef0123456789abcdef01234567\"}"));
        server.start();

        RepositoryAuditWorker worker = new RepositoryAuditWorker(HttpClient.newHttpClient(),
                "http://127.0.0.1:" + server.getAddress().getPort());
        WorkerResult result = worker.execute(new WorkerContext("AUDIT-1", "Audit kelvinka38/bios", Instant.now()));

        assertEquals("PASS", result.status());
        assertTrue(result.evidence().contains("source=github-api"));
        assertTrue(result.evidence().contains("repository=kelvinka38/bios"));
        assertTrue(result.evidence().contains("commitSha=0123456789abcdef0123456789abcdef01234567"));
        assertTrue(result.evidence().contains("metadataHttpStatus=200"));
        assertTrue(result.evidence().contains("commitHttpStatus=200"));
    }

    @Test
    void missingRepositoryFailsClosed() {
        RepositoryAuditWorker worker = new RepositoryAuditWorker();
        WorkerResult result = worker.execute(new WorkerContext("AUDIT-2", "Audit repository", Instant.now()));
        assertEquals("FAILED", result.status());
        assertTrue(result.evidence().contains("repository target missing"));
    }

    @Test
    void upstreamFailureCannotBecomePass() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/repos/kelvinka38/bios", exchange -> respond(exchange, 503, "{}"));
        server.start();
        RepositoryAuditWorker worker = new RepositoryAuditWorker(HttpClient.newHttpClient(),
                "http://127.0.0.1:" + server.getAddress().getPort());

        WorkerResult result = worker.execute(new WorkerContext("AUDIT-3", "Audit https://github.com/kelvinka38/bios", Instant.now()));
        assertEquals("FAILED", result.status());
        assertTrue(result.evidence().contains("HTTP 503"));
        assertFalse(result.evidence().contains("verdict=PASS"));
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}