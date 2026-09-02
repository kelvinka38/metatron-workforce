package com.metatron.workforce.runtime;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteRuntimeExecutorTest {

    @Test
    void executesAttributableCommandWithActualWorkAsynchronouslyOverHttp() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/execute", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String request = new String(body, StandardCharsets.UTF_8);
            assertTrue(request.contains("\"executionId\":\"exec-001\""));
            assertTrue(request.contains("\"workerId\":\"worker-001\""));
            assertTrue(request.contains("\"runtimeId\":\"runtime-001\""));
            assertTrue(request.contains("\"workSpec\""));
            assertTrue(request.contains("\"stepId\":\"step-audit\""));
            assertTrue(request.contains("\"objective\":\"Audit the repository\""));
            assertTrue(request.contains("\"target\":\"kelvinka38/metatron-workforce\""));
            assertTrue(request.contains("\"requiredCapability\":\"repository.read\""));
            assertTrue(request.contains("\"acceptanceCriteria\":[\"repository inspected\"]"));
            assertTrue(request.contains("\"evidenceRequirements\":[\"source references\"]"));
            byte[] response = "accepted".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(202, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            URI endpoint = URI.create("http://localhost:" + server.getAddress().getPort() + "/execute");
            RemoteRuntimeExecutor executor = new RemoteRuntimeExecutor();
            ExecutionWorkSpec work = new ExecutionWorkSpec(
                    "step-audit",
                    "Audit the repository",
                    "kelvinka38/metatron-workforce",
                    "repository.read",
                    List.of(),
                    ExecutionWorkSpec.Consequence.READ_ONLY,
                    List.of("repository inspected"),
                    List.of("source references"));

            var response = executor.executeAsync(
                            endpoint,
                            new RuntimeExecutionCommand("exec-001", "worker-001", "runtime-001", work))
                    .get(5, TimeUnit.SECONDS);

            assertEquals(202, response.statusCode());
            assertEquals("accepted", response.body());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void runtimeCommandCannotExistWithoutActualWork() {
        assertThrows(NullPointerException.class,
                () -> new RuntimeExecutionCommand("exec-001", "worker-001", "runtime-001", null));
    }
}
