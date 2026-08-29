package com.metatron.workforce.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class GatewayEgressClientTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void missingAuthorizationFailsClosedBeforeNetworkCrossing() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/allowed", exchange -> fail("network must not be reached without authorization"));
        server.start();
        GatewayEgressClient client = new GatewayEgressClient(HttpClient.newHttpClient(),
                "http://127.0.0.1:" + server.getAddress().getPort(), "secret", "");
        var result = client.get("/allowed", "application/json");
        assertTrue(result.denied());
        assertEquals("authorization_missing", result.denialReason());
        assertTrue(result.provenance().isBlank());
    }

    @Test
    void credentialIsAppliedInsideBoundaryButNeverReturnedInProvenance() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/allowed", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "ok".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        GatewayEgressClient client = new GatewayEgressClient(HttpClient.newHttpClient(),
                "http://127.0.0.1:" + server.getAddress().getPort(), "secret-token", "AUTH-READ");
        var result = client.get("/allowed", "text/plain");
        assertFalse(result.denied());
        assertEquals(200, result.statusCode());
        assertEquals("Bearer secret-token", auth.get());
        assertTrue(result.provenance().contains("authorization=AUTH-READ"));
        assertTrue(result.provenance().contains("credential=isolated"));
        assertFalse(result.provenance().contains("secret-token"));
    }
}
