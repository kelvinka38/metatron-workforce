package com.metatron.workforce.interaction.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/** Durable per-conversation memory for interactive Telegram sessions. */
public final class PersistentTelegramConversationMemory {
    private static final int MAX_STORED_TURNS = 200;
    private final Path root;
    private final ObjectMapper objectMapper;

    public PersistentTelegramConversationMemory(Path root, ObjectMapper objectMapper) {
        this.root = Objects.requireNonNull(root, "root");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public synchronized String context(String conversationId, int maxTurns, int maxChars) {
        ArrayNode turns = read(conversationId);
        StringBuilder out = new StringBuilder();
        int start = Math.max(0, turns.size() - Math.max(1, maxTurns));
        for (int i = start; i < turns.size(); i++) {
            JsonNode turn = turns.get(i);
            append(out, "User", turn.path("user").asText(""), maxChars);
            append(out, "Metatron", turn.path("assistant").asText(""), maxChars);
            if (out.length() >= maxChars) break;
        }
        return out.toString().trim();
    }

    public synchronized void appendTurn(String conversationId, String user, String assistant) {
        ArrayNode turns = read(conversationId);
        ObjectNode turn = objectMapper.createObjectNode();
        turn.put("at", Instant.now().toString());
        turn.put("user", Objects.requireNonNullElse(user, "").trim());
        turn.put("assistant", Objects.requireNonNullElse(assistant, "").trim());
        turns.add(turn);
        while (turns.size() > MAX_STORED_TURNS) turns.remove(0);
        write(conversationId, turns);
    }

    private void append(StringBuilder out, String role, String text, int maxChars) {
        if (text == null || text.isBlank() || out.length() >= maxChars) return;
        int remaining = maxChars - out.length();
        String value = text.length() <= remaining ? text : text.substring(0, remaining);
        out.append(role).append(": ").append(value).append('\n');
    }

    private ArrayNode read(String conversationId) {
        try {
            Path file = file(conversationId);
            if (!Files.exists(file)) return objectMapper.createArrayNode();
            JsonNode node = objectMapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
            return node != null && node.isArray() ? (ArrayNode) node : objectMapper.createArrayNode();
        } catch (Exception failure) {
            return objectMapper.createArrayNode();
        }
    }

    private void write(String conversationId, ArrayNode turns) {
        try {
            Files.createDirectories(root);
            Path target = file(conversationId);
            Path temp = Files.createTempFile(root, "conversation-", ".tmp");
            Files.writeString(temp, objectMapper.writeValueAsString(turns), StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("telegram_memory_write_failed", failure);
        }
    }

    private Path file(String conversationId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(conversationId.getBytes(StandardCharsets.UTF_8));
            return root.resolve(HexFormat.of().formatHex(digest) + ".json");
        } catch (Exception impossible) {
            throw new IllegalStateException("telegram_memory_key_failed", impossible);
        }
    }
}
