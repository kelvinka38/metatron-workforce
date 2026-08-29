package com.metatron.workforce.interaction.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramExecutionPathTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void auditGatewayCommandReachesConcreteReadCapabilityWithoutManufacturedAuthorization() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/g4/health", exchange -> {
            byte[] body = "{\"status\":\"UP\",\"gateway\":\"G4\"}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();

        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/g4/health";
        TelegramIntelligenceResponder responder = new TelegramIntelligenceResponder(
                "", "", "", "AUTO", "", "", "", new ObjectMapper(), url, "");

        String response = responder.respond("telegram-human", "audit g4 gateway", "update:42");

        assertTrue(response.contains("METATRON GATEWAY AUDIT RESULT"));
        assertTrue(response.contains("capability=gateway.audit.read"));
        assertTrue(response.contains("success=true"));
        assertTrue(response.contains("status=200"));
        assertTrue(response.contains("G4"));
    }

    @Test
    void auditGatewayCommandFailsClosedWhenCapabilityIsNotConfigured() {
        TelegramIntelligenceResponder responder = new TelegramIntelligenceResponder(
                "", "", "", "AUTO", "", "", "", new ObjectMapper());

        String response = responder.respond("telegram-human", "audit g4 gateway", "update:43");

        assertTrue(response.contains("METATRON GATEWAY AUDIT BLOCKED"));
        assertTrue(response.contains("gateway.audit.read"));
    }

    @Test
    void naturalLanguageDeployIntentPreservesExecutionSemanticsButCannotCreateAuthority() {
        TelegramIntelligenceResponder responder = new TelegramIntelligenceResponder(
                "", "", "", "AUTO", "", "", "", new ObjectMapper());

        String response = responder.respond("telegram-human", "fix it and deploy", "update:44");

        assertTrue(response.contains("METATRON EXECUTION BLOCKED"));
        assertTrue(response.contains("EXECUTION_ADMISSION_REQUIRED"));
        assertTrue(response.contains("fix it and deploy"));
    }

    @Test
    void naturalLanguageAuthorizationIntentCannotBecomeInstitutionalAuthority() {
        TelegramIntelligenceResponder responder = new TelegramIntelligenceResponder(
                "", "", "", "AUTO", "", "", "", new ObjectMapper());

        String response = responder.respond("telegram-human", "I authorize deployment", "update:45");

        assertTrue(response.contains("METATRON EXECUTION BLOCKED"));
        assertTrue(response.contains("EXECUTION_ADMISSION_REQUIRED"));
    }

    @Test
    void decisionIntentRequiresInstitutionalAuthorityInsteadOfChannelIdentity() {
        TelegramIntelligenceResponder responder = new TelegramIntelligenceResponder(
                "", "", "", "AUTO", "", "", "", new ObjectMapper());

        String response = responder.respond("telegram-human", "should we proceed", "update:46");

        assertTrue(response.contains("METATRON DECISION BLOCKED"));
        assertTrue(response.contains("INSTITUTIONAL_AUTHORITY_REQUIRED"));
    }
}
