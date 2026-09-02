package com.metatron.workforce.action;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BoundedInfrastructureActionAdapterTest {
    private static final String WORKER = "WORKER-ENGINEERING";
    private static final String AUTH = "authorization:engineering:test";

    @Test
    void boundedWebApiActionCallsOnlyAdmittedDestinationAndDoesNotLeakCredential() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/allowed/status", exchange -> {
            String secret = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] body = ("{\"ok\":true,\"credentialSeen\":" + (secret != null) + "}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            BoundedHttpAction action = new BoundedHttpAction(
                    "web.api.read", ActionFabric.Consequence.READ_ONLY,
                    Set.of(WORKER), Set.of(AUTH),
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "GET", "/allowed", Map.of("Authorization", "Bearer TOP-SECRET"), "application/json");
            ActionFabric fabric = new ActionFabric(List.of(action));

            ActionFabric.ActionObservation observation = fabric.execute(new ActionFabric.ActionRequest(
                    "web.api.read", WORKER, "assignment-1", AUTH, "objective-1", "step-1",
                    "idempotency-1", false, Map.of("path", "/allowed/status")));

            assertTrue(observation.success());
            assertEquals("200", observation.outputs().get("httpStatus"));
            assertTrue(observation.outputs().get("responseBody").contains("credentialSeen"));
            String allEvidence = String.join("\n", observation.evidenceReferences());
            assertTrue(allEvidence.contains("credential=isolated"));
            assertFalse(allEvidence.contains("TOP-SECRET"));
            assertFalse(observation.summary().contains("TOP-SECRET"));

            assertThrows(SecurityException.class, () -> fabric.execute(new ActionFabric.ActionRequest(
                    "web.api.read", WORKER, "assignment-1", AUTH, "objective-1", "step-1",
                    "idempotency-2", false, Map.of("path", "/outside"))));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void infrastructureCatalogOmitsCredentialedToolsWhenCredentialsAreUnavailable() {
        List<ActionFabric.Action> minimal = InstitutionalActionAdapters.engineeringControlPlane(
                WORKER, AUTH, "", "", "");
        assertEquals(List.of(InstitutionalActionAdapters.RUNTIME_HEALTH_READ),
                minimal.stream().map(ActionFabric.Action::actionRef).toList());

        List<String> configured = InstitutionalActionAdapters.engineeringControlPlane(
                        WORKER, AUTH, "github-token", "cloudflare-token", "account_12345678")
                .stream().map(ActionFabric.Action::actionRef).toList();
        assertTrue(configured.contains(InstitutionalActionAdapters.RUNTIME_HEALTH_READ));
        assertTrue(configured.contains(InstitutionalActionAdapters.GITHUB_BUILD_DISPATCH));
        assertTrue(configured.contains(InstitutionalActionAdapters.GITHUB_DEPLOY_DISPATCH));
        assertTrue(configured.contains(InstitutionalActionAdapters.CLOUDFLARE_ACCOUNT_READ));
    }

    @Test
    void readOnlyHttpActionCannotBeConstructedWithMutationMethod() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedHttpAction(
                "bad", ActionFabric.Consequence.READ_ONLY, Set.of(WORKER), Set.of(AUTH),
                HttpClient.newHttpClient(), "https://example.com", "POST", "/", Map.of(), "application/json"));
    }
}
