package com.metatron.workforce.execution;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionCapabilityTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void admittedCommandDispatchesToConcreteGatewayCapability() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/health", exchange -> {
            byte[] body = "{\"status\":\"UP\"}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();

        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/health";
        ExecutionCapabilityRegistry registry = new ExecutionCapabilityRegistry(
                Map.of("gateway.audit.read", new GatewayAuditCapability(url, "")));
        ExecutionCommand command = new ExecutionCommand("exec-1", "gateway.audit.read", Instant.now());

        ExecutionResult result = new ExecutionCommandService().dispatch(command, ExecutionState.ADMITTED, registry);

        assertTrue(result.success());
        assertEquals(ExecutionState.COMPLETED, result.state());
        assertEquals("gateway.audit.read", result.capability());
        assertTrue(result.evidence().contains("status=200"));
        assertTrue(result.evidence().contains("status"));
    }

    @Test
    void unregisteredCapabilityCannotExecute() {
        ExecutionCommand command = new ExecutionCommand("exec-2", "unknown", Instant.now());
        ExecutionCapabilityRegistry registry = new ExecutionCapabilityRegistry(Map.of());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new ExecutionCommandService().dispatch(command, ExecutionState.ADMITTED, registry));

        assertTrue(error.getMessage().contains("execution_capability_not_registered"));
    }
}
