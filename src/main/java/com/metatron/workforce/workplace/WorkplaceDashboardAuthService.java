package com.metatron.workforce.workplace;

import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WorkplaceDashboardAuthService {
    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final Duration SESSION_TTL = Duration.ofHours(12);
    private static final Duration REQUEST_COOLDOWN = Duration.ofSeconds(45);
    private final SecureRandom random = new SecureRandom();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, Instant> sessions = new ConcurrentHashMap<>();
    private volatile String code;
    private volatile Instant codeExpiresAt = Instant.EPOCH;
    private volatile Instant lastRequestedAt = Instant.EPOCH;

    public synchronized void requestCode() {
        Instant now = Instant.now();
        if (now.isBefore(lastRequestedAt.plus(REQUEST_COOLDOWN))) {
            throw new IllegalStateException("login code requested too recently");
        }
        String botToken = requiredEnv("TELEGRAM_BOT_TOKEN");
        String userId = requiredEnv("TELEGRAM_ALLOWED_USER_ID");
        code = String.format("%06d", random.nextInt(1_000_000));
        codeExpiresAt = now.plus(CODE_TTL);
        lastRequestedAt = now;
        String text = "Metatron Workplace login code: " + code + "\nExpires in 5 minutes.";
        String body = "chat_id=" + enc(userId) + "&text=" + enc(text);
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.telegram.org/bot" + botToken + "/sendMessage"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2 || !response.body().contains("\"ok\":true")) {
                code = null;
                throw new IllegalStateException("Telegram login delivery failed");
            }
        } catch (Exception e) {
            code = null;
            throw new IllegalStateException("Telegram login delivery failed", e);
        }
    }

    public synchronized String verify(String suppliedCode) {
        Instant now = Instant.now();
        if (code == null || now.isAfter(codeExpiresAt) || suppliedCode == null || !constantTimeEquals(code, suppliedCode.trim())) {
            throw new SecurityException("invalid or expired login code");
        }
        code = null;
        String token = UUID.randomUUID() + "." + UUID.randomUUID();
        sessions.put(token, now.plus(SESSION_TTL));
        cleanup(now);
        return token;
    }

    public boolean valid(String token) {
        if (token == null || token.isBlank()) return false;
        Instant expiry = sessions.get(token);
        if (expiry == null) return false;
        if (Instant.now().isAfter(expiry)) { sessions.remove(token); return false; }
        return true;
    }

    public void logout(String token) { if (token != null) sessions.remove(token); }

    private void cleanup(Instant now) { sessions.entrySet().removeIf(e -> now.isAfter(e.getValue())); }
    private static String requiredEnv(String key) { String v = System.getenv(key); if (v == null || v.isBlank()) throw new IllegalStateException(key + " is not configured"); return v; }
    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int result = 0; for (int i=0;i<a.length();i++) result |= a.charAt(i) ^ b.charAt(i); return result == 0;
    }
}
