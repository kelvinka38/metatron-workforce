package com.metatron.workforce.workers.audit;

import com.metatron.workforce.action.ActionJournal;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.workers.WorkerResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RepositoryAuditCognitiveWorkerTest {
    private HttpServer server;

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test
    void eachExternalReadIsASeparatelyAttributedCognitiveAction() throws Exception {
        AtomicReference<String> authHeader = new AtomicReference<>();
        start(authHeader);
        List<String> journal = new ArrayList<>();
        ActionJournal capture = (objectiveId, workStepId, workerId, assignmentReference,
                                 authorizationReference, idempotencyKey, cycle) -> journal.add(
                objectiveId + "|" + workStepId + "|" + workerId + "|" + assignmentReference + "|"
                        + authorizationReference + "|" + cycle.number() + "|" + cycle.thought().actionRef()
                        + "|" + cycle.reflection().decision());

        RepositoryAuditCognitiveWorker worker = new RepositoryAuditCognitiveWorker(
                HttpClient.newHttpClient(), base(), "secret-token", "AUTH-READ", capture);
        ExecutionWorkSpec spec = new ExecutionWorkSpec(
                "STEP-AUDIT", "Audit kelvinka38/bios", "kelvinka38/bios", "repository.audit.read",
                List.of(), ExecutionWorkSpec.Consequence.READ_ONLY);

        WorkerResult result = worker.execute("OBJ-1", "WORK-1", "ASSIGN-1", "kelvinka38/bios", spec);

        assertEquals("PASS", result.status());
        assertTrue(result.evidence().contains("executionModel=cognitive-action-fabric"));
        assertTrue(result.evidence().contains("cognitiveActionCount=5"));
        assertTrue(result.evidence().contains("contentFilesRead=2"));
        assertTrue(result.evidence().contains("observedPaths=BIOS_SOT.md,src/Main.java"));
        assertFalse(result.evidence().contains("secret-token"));
        assertEquals("Bearer secret-token", authHeader.get());

        assertEquals(List.of(
                "github.repository.metadata.read",
                "github.repository.head.read",
                "github.repository.tree.read",
                "github.repository.content.inspect",
                "github.repository.content.inspect"),
                journal.stream().map(row -> row.split("\\|", -1)[6]).toList());
        assertTrue(journal.stream().allMatch(row -> row.startsWith(
                "OBJ-1|STEP-AUDIT|WORKER-REPOSITORY-AUDITOR|ASSIGN-1|AUTH-READ|")));
        assertTrue(journal.getLast().endsWith("|COMPLETE"));
        assertTrue(journal.subList(0, journal.size() - 1).stream().allMatch(row -> row.endsWith("|CONTINUE")));
    }

    @Test
    void oneUnavailableContentFileIsRecordedWithoutDiscardingSubstantiveAudit() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/repos/kelvinka38/bios", exchange -> respond(exchange, 200, "{\"default_branch\":\"main\"}"));
        server.createContext("/repos/kelvinka38/bios/commits/main", exchange -> respond(exchange, 200,
                "{\"sha\":\"0123456789abcdef0123456789abcdef01234567\"}"));
        server.createContext("/repos/kelvinka38/bios/git/trees/0123456789abcdef0123456789abcdef01234567",
                exchange -> respond(exchange, 200,
                        "{\"tree\":[{\"path\":\"BIOS_SOT.md\"},{\"path\":\"missing.md\"},{\"path\":\"src/Main.java\"}]}"));
        server.createContext("/repos/kelvinka38/bios/contents/BIOS_SOT.md", exchange -> respond(exchange, 200,
                "# BIOS SOURCE OF TRUTH\ncanonical"));
        server.createContext("/repos/kelvinka38/bios/contents/missing.md", exchange -> respond(exchange, 429,
                "rate limited"));
        server.createContext("/repos/kelvinka38/bios/contents/src/Main.java", exchange -> respond(exchange, 200,
                "class Main {}"));
        server.start();

        RepositoryAuditCognitiveWorker worker = new RepositoryAuditCognitiveWorker(
                HttpClient.newHttpClient(), base(), "secret-token", "AUTH-READ", ActionJournal.noop());
        ExecutionWorkSpec spec = new ExecutionWorkSpec(
                "STEP-AUDIT", "Audit kelvinka38/bios", "kelvinka38/bios", "repository.audit.read",
                List.of(), ExecutionWorkSpec.Consequence.READ_ONLY);

        WorkerResult result = worker.execute("OBJ-PARTIAL", "WORK-PARTIAL", "ASSIGN-PARTIAL", "kelvinka38/bios", spec);

        assertEquals("PASS", result.status());
        assertTrue(result.evidence().contains("contentFilesRead=2"));
        assertTrue(result.evidence().contains("contentFilesSkipped=1"));
        assertTrue(result.evidence().contains("UNREADABLE_CONTENT_FILES=1"));
        assertTrue(result.evidence().contains("skippedPaths=missing.md"));
        assertTrue(result.evidence().contains("verdict=PASS"));
    }

    @Test
    void noReadableContentStillFailsClosed() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/repos/kelvinka38/bios", exchange -> respond(exchange, 200, "{\"default_branch\":\"main\"}"));
        server.createContext("/repos/kelvinka38/bios/commits/main", exchange -> respond(exchange, 200,
                "{\"sha\":\"0123456789abcdef0123456789abcdef01234567\"}"));
        server.createContext("/repos/kelvinka38/bios/git/trees/0123456789abcdef0123456789abcdef01234567",
                exchange -> respond(exchange, 200, "{\"tree\":[{\"path\":\"README.md\"}]}"));
        server.createContext("/repos/kelvinka38/bios/contents/README.md", exchange -> respond(exchange, 429,
                "rate limited"));
        server.start();

        RepositoryAuditCognitiveWorker worker = new RepositoryAuditCognitiveWorker(
                HttpClient.newHttpClient(), base(), "secret-token", "AUTH-READ", ActionJournal.noop());
        ExecutionWorkSpec spec = new ExecutionWorkSpec(
                "STEP-AUDIT", "Audit kelvinka38/bios", "kelvinka38/bios", "repository.audit.read",
                List.of(), ExecutionWorkSpec.Consequence.READ_ONLY);

        WorkerResult result = worker.execute("OBJ-NONE", "WORK-NONE", "ASSIGN-NONE", "kelvinka38/bios", spec);

        assertEquals("FAILED", result.status());
        assertTrue(result.evidence().contains("cognitive audit did not establish readable repository content"));
        assertTrue(result.evidence().contains("verdict=FAILED"));
    }

    @Test
    void representativeAuditCapsContentReadsToTwentyFourFiles() throws Exception {
        AtomicInteger contentCalls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/repos/kelvinka38/bios", exchange -> respond(exchange, 200, "{\"default_branch\":\"main\"}"));
        server.createContext("/repos/kelvinka38/bios/commits/main", exchange -> respond(exchange, 200,
                "{\"sha\":\"0123456789abcdef0123456789abcdef01234567\"}"));
        StringBuilder tree = new StringBuilder("{\"tree\":[");
        for (int i = 1; i <= 40; i++) {
            if (i > 1) tree.append(',');
            tree.append("{\"path\":\"docs/file-").append(i).append(".md\"}");
        }
        tree.append("]}");
        server.createContext("/repos/kelvinka38/bios/git/trees/0123456789abcdef0123456789abcdef01234567",
                exchange -> respond(exchange, 200, tree.toString()));
        server.createContext("/repos/kelvinka38/bios/contents/", exchange -> {
            contentCalls.incrementAndGet();
            respond(exchange, 200, "# bounded audit content");
        });
        server.start();

        RepositoryAuditCognitiveWorker worker = new RepositoryAuditCognitiveWorker(
                HttpClient.newHttpClient(), base(), "secret-token", "AUTH-READ", ActionJournal.noop());
        ExecutionWorkSpec spec = new ExecutionWorkSpec(
                "STEP-AUDIT", "Audit kelvinka38/bios", "kelvinka38/bios", "repository.audit.read",
                List.of(), ExecutionWorkSpec.Consequence.READ_ONLY);

        WorkerResult result = worker.execute("OBJ-BOUNDED", "WORK-BOUNDED", "ASSIGN-BOUNDED", "kelvinka38/bios", spec);

        assertEquals("PASS", result.status());
        assertEquals(24, contentCalls.get());
        assertTrue(result.evidence().contains("contentFilesRead=24"));
        assertTrue(result.evidence().contains("cognitiveActionCount=27"));
    }

    @Test
    void failedTreeObservationStopsWithoutPretendingCompletion() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/repos/kelvinka38/bios", exchange -> respond(exchange, 200, "{\"default_branch\":\"main\"}"));
        server.createContext("/repos/kelvinka38/bios/commits/main", exchange -> respond(exchange, 200,
                "{\"sha\":\"0123456789abcdef0123456789abcdef01234567\"}"));
        server.createContext("/repos/kelvinka38/bios/git/trees/0123456789abcdef0123456789abcdef01234567",
                exchange -> respond(exchange, 503, "{}"));
        server.start();

        List<String> decisions = new ArrayList<>();
        ActionJournal capture = (a, b, c, d, e, f, cycle) -> decisions.add(cycle.reflection().decision().name());
        RepositoryAuditCognitiveWorker worker = new RepositoryAuditCognitiveWorker(
                HttpClient.newHttpClient(), base(), "", "AUTH-READ", capture);
        ExecutionWorkSpec spec = new ExecutionWorkSpec(
                "STEP-AUDIT", "Audit kelvinka38/bios", "kelvinka38/bios", "repository.audit.read",
                List.of(), ExecutionWorkSpec.Consequence.READ_ONLY);

        WorkerResult result = worker.execute("OBJ-FAIL", "WORK-FAIL", "ASSIGN-FAIL", "kelvinka38/bios", spec);
        assertEquals("FAILED", result.status());
        assertTrue(result.evidence().contains("cognitive audit did not establish readable repository content"));
        assertEquals("FAILED", decisions.getLast());
    }

    private void start(AtomicReference<String> authHeader) throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/repos/kelvinka38/bios", exchange -> {
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"default_branch\":\"main\"}");
        });
        server.createContext("/repos/kelvinka38/bios/commits/main", exchange -> respond(exchange, 200,
                "{\"sha\":\"0123456789abcdef0123456789abcdef01234567\"}"));
        server.createContext("/repos/kelvinka38/bios/git/trees/0123456789abcdef0123456789abcdef01234567", exchange -> respond(exchange, 200,
                "{\"tree\":[{\"path\":\"BIOS_SOT.md\"},{\"path\":\"src/Main.java\"}]}"));
        server.createContext("/repos/kelvinka38/bios/contents/BIOS_SOT.md", exchange -> respond(exchange, 200,
                "# BIOS SOURCE OF TRUTH\ncanonical"));
        server.createContext("/repos/kelvinka38/bios/contents/src/Main.java", exchange -> respond(exchange, 200,
                "class Main {} // TODO verify"));
        server.start();
    }

    private String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) { out.write(bytes); }
    }
}
