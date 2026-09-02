package com.metatron.workforce.action;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Canonical bounded adapters for infrastructure tools used by Cognitive Workers.
 *
 * <p>These adapters deliberately expose institutional control planes instead of a raw shell:
 * build/test and deploy are GitHub workflow-dispatch actions, server access is the local runtime
 * health API, Cloudflare is account-scoped API access, and external information is destination-
 * scoped HTTPS. Credentials are present only when configured and are never put into the Action
 * catalog or observations.</p>
 */
public final class InstitutionalActionAdapters {
    public static final String GITHUB_BUILD_DISPATCH = "github.workflow.ci.dispatch";
    public static final String GITHUB_DEPLOY_DISPATCH = "github.workflow.production-deploy.dispatch";
    public static final String RUNTIME_HEALTH_READ = "runtime.server.health.read";
    public static final String CLOUDFLARE_ACCOUNT_READ = "cloudflare.account.api.read";

    private InstitutionalActionAdapters() {}

    public static List<ActionFabric.Action> engineeringControlPlane(String workerId,
                                                                     String authorizationReference,
                                                                     String githubToken,
                                                                     String cloudflareToken,
                                                                     String cloudflareAccountId) {
        String worker = require(workerId, "workerId");
        String auth = require(authorizationReference, "authorizationReference");
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        List<ActionFabric.Action> actions = new ArrayList<>();

        actions.add(new BoundedHttpAction(
                RUNTIME_HEALTH_READ, ActionFabric.Consequence.READ_ONLY,
                Set.of(worker), Set.of(auth), http,
                "http://127.0.0.1:8080", "GET", "/actuator/health", Map.of(), "application/json"));

        if (githubToken != null && !githubToken.isBlank()) {
            Map<String, String> githubHeaders = Map.of(
                    "Authorization", "Bearer " + githubToken.trim(),
                    "X-GitHub-Api-Version", "2022-11-28");
            actions.add(new BoundedHttpAction(
                    GITHUB_BUILD_DISPATCH, ActionFabric.Consequence.MUTATING,
                    Set.of(worker), Set.of(auth), http,
                    "https://api.github.com", "POST",
                    "/repos/kelvinka38/metatron-workforce/actions/workflows/ci.yml/dispatches",
                    githubHeaders, "application/vnd.github+json"));
            actions.add(new BoundedHttpAction(
                    GITHUB_DEPLOY_DISPATCH, ActionFabric.Consequence.MUTATING,
                    Set.of(worker), Set.of(auth), http,
                    "https://api.github.com", "POST",
                    "/repos/kelvinka38/metatron-workforce/actions/workflows/production-deploy.yml/dispatches",
                    githubHeaders, "application/vnd.github+json"));
        }

        if (cloudflareToken != null && !cloudflareToken.isBlank()
                && cloudflareAccountId != null && cloudflareAccountId.matches("[A-Za-z0-9_-]{8,64}")) {
            actions.add(new BoundedHttpAction(
                    CLOUDFLARE_ACCOUNT_READ, ActionFabric.Consequence.READ_ONLY,
                    Set.of(worker), Set.of(auth), http,
                    "https://api.cloudflare.com", "GET",
                    "/client/v4/accounts/" + cloudflareAccountId,
                    Map.of("Authorization", "Bearer " + cloudflareToken.trim()), "application/json"));
        }
        return List.copyOf(actions);
    }

    /** Build an allow-listed HTTPS GET action for one external web/API destination. */
    public static ActionFabric.Action webApiRead(String actionRef,
                                                 String workerId,
                                                 String authorizationReference,
                                                 String destinationBase,
                                                 String admittedPathPrefix,
                                                 Map<String, String> privateHeaders) {
        return new BoundedHttpAction(actionRef, ActionFabric.Consequence.READ_ONLY,
                Set.of(require(workerId, "workerId")), Set.of(require(authorizationReference, "authorizationReference")),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build(),
                destinationBase, "GET", admittedPathPrefix,
                privateHeaders == null ? Map.of() : privateHeaders, "application/json");
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
        return value.trim();
    }
}
