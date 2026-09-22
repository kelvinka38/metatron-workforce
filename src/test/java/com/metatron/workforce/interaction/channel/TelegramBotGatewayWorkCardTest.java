package com.metatron.workforce.interaction.channel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves TelegramBotGateway's real HTTP request shape for the live Work Card lifecycle against a
 * genuine local HTTP server, not a hand-constructed response -- the 2026-09-22 production incident
 * (a new Telegram message roughly every 5 seconds) was a payload-shape defect (an attached
 * ReplyKeyboardMarkup made the Work Card permanently non-editable) that no existing test caught.
 */
final class TelegramBotGatewayWorkCardTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<JsonNode> sendMessagePayloads = new ArrayList<>();
    private final List<JsonNode> editMessagePayloads = new ArrayList<>();
    private final AtomicLong nextMessageId = new AtomicLong(9000);
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendWorkCardNeverAttachesAnIncompatibleReplyKeyboard() throws Exception {
        TelegramBotGateway gateway = startFakeGatewayAcceptingEverySend();

        long messageId = gateway.sendWorkCard("chat-1", "📋 METATRON · WORK ORDER\n\nobjective_id=x");

        assertTrue(messageId >= 9000);
        assertEquals(1, sendMessagePayloads.size());
        JsonNode payload = sendMessagePayloads.get(0);
        assertEquals("chat-1", payload.path("chat_id").asText());
        assertEquals("📋 METATRON · WORK ORDER\n\nobjective_id=x", payload.path("text").asText());
        assertFalse(payload.has("reply_markup"),
                "a Work Card must never carry a ReplyKeyboardMarkup -- Telegram only allows "
                        + "editMessageText on a message with no incompatible reply_markup");
    }

    @Test
    void editWorkCardUsesEditMessageTextWithTheSameMessageIdAndNeverSendsANewMessage() throws Exception {
        TelegramBotGateway gateway = startFakeGatewayAcceptingEverySend();

        gateway.editWorkCard("chat-1", 4242L, "updated card text");

        assertEquals(1, editMessagePayloads.size());
        assertEquals(0, sendMessagePayloads.size(), "a normal edit must never call sendMessage");
        JsonNode payload = editMessagePayloads.get(0);
        assertEquals("chat-1", payload.path("chat_id").asText());
        assertEquals(4242L, payload.path("message_id").asLong());
        assertEquals("updated card text", payload.path("text").asText());
    }

    @Test
    void unchangedContentIsAHarmlessNoOpAndNeverCreatesAReplacement() throws Exception {
        startTelegramErrorServer("editMessageText", 400, "Bad Request: message is not modified");
        TelegramBotGateway gateway = gatewayForFakeServer();

        assertDoesNotThrow(() -> gateway.editWorkCard("chat-1", 4242L, "same text"));
        assertEquals(0, sendMessagePayloads.size());
    }

    @Test
    void deterministicMessageCannotBeEditedFailurePropagatesRatherThanBeingSwallowed() throws Exception {
        startTelegramErrorServer("editMessageText", 400, "Bad Request: message can't be edited");
        TelegramBotGateway gateway = gatewayForFakeServer();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> gateway.editWorkCard("chat-1", 4242L, "new text"));
        assertTrue(failure.getMessage().contains("message can't be edited"));
    }

    @Test
    void persistentWorkControlsAreDeliveredIndependentlyOfTheEditableWorkCard() throws Exception {
        TelegramBotGateway gateway = startFakeGatewayAcceptingEverySend();

        gateway.sendWorkCard("chat-1", "📋 METATRON · WORK ORDER\n\nobjective_id=x");
        assertFalse(sendMessagePayloads.get(0).has("reply_markup"));

        // Persistent Work controls are re-established through an ordinary control/status message,
        // never by attaching a keyboard to the live Work Card.
        gateway.send(new ChannelMessage("telegram", "chat-1", "📊 METATRON WORK\nLive monitoring started."));

        assertEquals(2, sendMessagePayloads.size());
        JsonNode controlPayload = sendMessagePayloads.get(1);
        Map<String, Object> replyMarkup = mapper.convertValue(
                controlPayload.path("reply_markup"), new TypeReference<Map<String, Object>>() {});
        assertEquals(TelegramBotGateway.workReplyMarkup(), replyMarkup,
                "the persistent Work keyboard must still be deliverable through the ordinary send(...) "
                        + "path, decoupled entirely from the Work Card's own (markup-free) payload");
    }

    private TelegramBotGateway startFakeGatewayAcceptingEverySend() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bottest-token/sendMessage", exchange -> {
            sendMessagePayloads.add(readJson(exchange));
            long messageId = nextMessageId.getAndIncrement();
            respond(exchange, 200, "{\"ok\":true,\"result\":{\"message_id\":" + messageId + "}}");
        });
        server.createContext("/bottest-token/editMessageText", exchange -> {
            editMessagePayloads.add(readJson(exchange));
            respond(exchange, 200, "{\"ok\":true,\"result\":{\"message_id\":1}}");
        });
        server.start();
        return gatewayForFakeServer();
    }

    private void startTelegramErrorServer(String method, int errorCode, String description) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bottest-token/" + method, exchange -> {
            if ("editMessageText".equals(method)) editMessagePayloads.add(readJson(exchange));
            else sendMessagePayloads.add(readJson(exchange));
            respond(exchange, 400, "{\"ok\":false,\"error_code\":" + errorCode + ",\"description\":\""
                    + description + "\"}");
        });
        server.start();
    }

    private TelegramBotGateway gatewayForFakeServer() {
        return new TelegramBotGateway("test-token", HttpClient.newHttpClient(), mapper,
                "http://127.0.0.1:" + server.getAddress().getPort());
    }

    private JsonNode readJson(HttpExchange exchange) {
        try {
            return mapper.readTree(exchange.getRequestBody());
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
