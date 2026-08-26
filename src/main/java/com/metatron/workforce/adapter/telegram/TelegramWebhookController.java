package com.metatron.workforce.adapter.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.phase3.ActorRef;
import com.metatron.workforce.phase3.AuthorizationContext;
import com.metatron.workforce.phase3.WorkplaceCommunicationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Map;

@RestController
@RequestMapping("/telegram")
public final class TelegramWebhookController {

    private final ObjectMapper mapper;
    private final TelegramWorkplaceAdapter adapter;
    private final HttpClient http = HttpClient.newHttpClient();

    private final String secret = env("TELEGRAM_WEBHOOK_SECRET", "");
    private final String botToken = env("TELEGRAM_BOT_TOKEN", "");
    private final String organizationId =
            env("METATRON_ORGANIZATION_ID", "ORG-PROD-001");

    public TelegramWebhookController(ObjectMapper mapper) {
        this.mapper = mapper;

        WorkplaceCommunicationService workplace =
                new WorkplaceCommunicationService(
                        (actor, target, org) ->
                                AuthorizationContext.allowed(
                                        "telegram-webhook-bootstrap"),
                        Clock.systemUTC());

        long allowedUser = Long.parseLong(
                env("TELEGRAM_ALLOWED_USER_ID", "0"));

        ActorRef human = new ActorRef(
                "HUMAN-TELEGRAM-" + allowedUser,
                ActorRef.ActorType.HUMAN);

        ActorRef worker = new ActorRef(
                "WORKER-TELEGRAM-01",
                ActorRef.ActorType.WORKER);

        ConfiguredTelegramIdentityResolver identities =
                new ConfiguredTelegramIdentityResolver(
                        allowedUser,
                        human,
                        worker,
                        organizationId);

        this.adapter = new TelegramWorkplaceAdapter(
                workplace,
                identities);
    }

    @PostMapping("/webhook")
    public ResponseEntity<?> webhook(
            @RequestHeader(
                    value = "X-Telegram-Bot-Api-Secret-Token",
                    required = false)
            String suppliedSecret,
            @RequestBody String body) {

        if (secret.isBlank() || suppliedSecret == null ||
                !MessageDigest.isEqual(
                        secret.getBytes(StandardCharsets.UTF_8),
                        suppliedSecret.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("status", "UNAUTHORIZED"));
        }

        try {
            TelegramUpdate update =
                    mapper.readValue(body, TelegramUpdate.class);

            TelegramUpdate.TelegramMessage message = update.message();

            if (message == null ||
                    message.chat() == null ||
                    message.text() == null ||
                    message.text().isBlank()) {
                return ResponseEntity.ok(Map.of(
                        "status", "IGNORED"));
            }

            TelegramWorkplaceAdapter.Result result =
                    adapter.accept(update);

            if (result.status() != TelegramWorkplaceAdapter.Status.ACCEPTED) {
                return ResponseEntity.ok(Map.of(
                        "status", result.status().name(),
                        "reason", result.reason()));
            }

            String reply =
                    "METATRON E2E OK\n\nReceived: " + result.text();

            sendMessage(message.chat().id(), reply);

            return ResponseEntity.ok(Map.of(
                    "status", "REPLIED",
                    "updateId", result.updateId(),
                    "messageId", result.message().messageId()));

        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("status", "FORBIDDEN"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "status", "ERROR",
                            "error", e.getClass().getSimpleName()));
        }
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "telegramBotConfigured", !botToken.isBlank(),
                "webhookSecretConfigured", !secret.isBlank());
    }

    private void sendMessage(long chatId, String text)
            throws Exception {

        if (botToken.isBlank()) {
            throw new IllegalStateException(
                    "TELEGRAM_BOT_TOKEN is not configured");
        }

        String payload = mapper.writeValueAsString(
                Map.of(
                        "chat_id", chatId,
                        "text", text));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(
                        "https://api.telegram.org/bot"
                                + botToken
                                + "/sendMessage"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response =
                http.send(request,
                        HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 ||
                response.statusCode() >= 300) {
            throw new IllegalStateException(
                    "Telegram sendMessage failed: HTTP "
                            + response.statusCode());
        }

        JsonNode json = mapper.readTree(response.body());

        if (!json.path("ok").asBoolean(false)) {
            throw new IllegalStateException(
                    "Telegram sendMessage returned ok=false");
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank()
                ? fallback
                : value;
    }
}
