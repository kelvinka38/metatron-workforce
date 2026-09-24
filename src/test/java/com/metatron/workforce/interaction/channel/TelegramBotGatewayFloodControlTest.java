package com.metatron.workforce.interaction.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production 2026-09-24: Telegram answered "429 Too Many Requests: retry after 14083" (~4 h). Every send
 * path kept calling Telegram during the window, which only lengthens the ban. The gateway must hold a
 * bot-wide gate for the whole retry-after window and fail locally without touching Telegram.
 */
final class TelegramBotGatewayFloodControlTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void aFloodControl429BlocksEverySendPathLocallyUntilTheRetryAfterWindowElapses() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger rateLimited = new AtomicInteger(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            boolean limited = rateLimited.getAndDecrement() > 0;
            String body = limited
                    ? "{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests: retry after 30\",\"parameters\":{\"retry_after\":30}}"
                    : "{\"ok\":true,\"result\":{\"message_id\":77}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(limited ? 429 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        AtomicLong now = new AtomicLong(1_000_000L);
        TelegramBotGateway gateway = new TelegramBotGateway("token", HttpClient.newHttpClient(), new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort(), now::get);

        IllegalStateException first = assertThrows(IllegalStateException.class,
                () -> gateway.send(new ChannelMessage("telegram", "chat-1", "hello")));
        assertTrue(first.getMessage().contains("429"), first.getMessage());
        assertEquals(1, calls.get());

        now.addAndGet(10_000L);
        IllegalStateException gated = assertThrows(IllegalStateException.class,
                () -> gateway.sendWorkCard("chat-1", "📋 METATRON · WORK ORDER"));
        assertTrue(gated.getMessage().matches("(?s).*\\b429\\b.*retry after 20.*"),
                "the local gate keeps the 429 retry-after shape the Work Card monitor already honors: " + gated.getMessage());
        assertThrows(IllegalStateException.class, () -> gateway.editWorkCard("chat-1", 5L, "card"));
        assertEquals(1, calls.get(), "no call may reach Telegram inside the retry-after window");

        now.addAndGet(21_000L);
        assertEquals(77L, gateway.sendWorkCard("chat-1", "📋 METATRON · WORK ORDER"));
        assertEquals(2, calls.get(), "sending resumes once the window has elapsed");
    }
}
