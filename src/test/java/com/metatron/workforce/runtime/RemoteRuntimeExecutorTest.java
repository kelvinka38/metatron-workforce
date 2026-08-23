package com.metatron.workforce.runtime;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteRuntimeExecutorTest {

    @Test
    void executesAttributableCommandAsynchronouslyOverHttp() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/execute", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String request = new String(body, StandardCharsets.UTF_8);
            assertTrue(request.contains("exec-001"));
            assertTrue(request.contains("worker-001"));
            assertTrue(request.contains("runtime-001"));
            byte[] response = "accepted".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(202, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            URI endpoint = URI.create("http://localhost:" + server.getAddress().getPort() + "/execute");
            RemoteRuntimeExecutor executor = new RemoteRuntimeExecutor();
            var response = executor.executeAsync(
                    endpoint,
                    new RuntimeExecutionCommand("exec-001", "worker-001", "runtime-001"))
                    .get(5, TimeUnit.SECONDS);

            assertEquals(202, response.statusCode());
            assertEquals("accepted", response.body());
        } finally {
            server.stop(0);
        }
    }
}
