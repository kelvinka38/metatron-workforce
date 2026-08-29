package com.metatron.workforce.interaction.memory;

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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Durable filesystem-backed memory keyed by canonical conversation id. */
public final class PersistentConversationMemoryStore implements ConversationMemoryStore {
    private static final int MAX_STORED_TURNS = 500;

    private final Path root;
    private final ObjectMapper objectMapper;

    public PersistentConversationMemoryStore(Path root, ObjectMapper objectMapper) {
        this.root = Objects.requireNonNull(root, "root");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public synchronized String context(String conversationId, int maxTurns, int maxChars) {
        ArrayNode turns = read(conversationId);
        return renderRecent(turns, maxTurns, maxChars);
    }

    @Override
    public synchronized String contextFor(String conversationId, String currentText,
                                          int maxRecentTurns, int maxRelevantTurns, int maxChars) {
        ArrayNode turns = read(conversationId);
        if (turns.isEmpty()) return "";

        int recentStart = Math.max(0, turns.size() - Math.max(1, maxRecentTurns));
        Set<String> queryTokens = tokens(currentText);
        List<ScoredTurn> relevant = new ArrayList<>();
        if (!queryTokens.isEmpty() && recentStart > 0 && maxRelevantTurns > 0) {
            for (int i = 0; i < recentStart; i++) {
                JsonNode turn = turns.get(i);
                String combined = turn.path("human").asText("") + " " + turn.path("metatron").asText("");
                int score = overlap(queryTokens, tokens(combined));
                if (score > 0) relevant.add(new ScoredTurn(i, score, turn));
            }
            relevant.sort(Comparator.comparingInt(ScoredTurn::score).reversed().thenComparingInt(ScoredTurn::index).reversed());
            if (relevant.size() > maxRelevantTurns) relevant = new ArrayList<>(relevant.subList(0, maxRelevantTurns));
            relevant.sort(Comparator.comparingInt(ScoredTurn::index));
        }

        StringBuilder out = new StringBuilder();
        if (!relevant.isEmpty()) {
            out.append("RELEVANT OLDER MEMORY:\n");
            for (ScoredTurn item : relevant) {
                appendTurn(out, item.turn(), maxChars);
                if (out.length() >= maxChars) return out.toString().trim();
            }
            out.append("\nRECENT CONVERSATION:\n");
        }

        for (int i = recentStart; i < turns.size(); i++) {
            appendTurn(out, turns.get(i), maxChars);
            if (out.length() >= maxChars) break;
        }
        return out.toString().trim();
    }

    @Override
    public synchronized void appendTurn(String conversationId, String humanText, String metatronText) {
        Objects.requireNonNull(conversationId, "conversationId");
        if (conversationId.isBlank()) throw new IllegalArgumentException("conversationId must not be blank");

        ArrayNode turns = read(conversationId);
        ObjectNode turn = objectMapper.createObjectNode();
        turn.put("at", Instant.now().toString());
        turn.put("human", Objects.requireNonNullElse(humanText, "").trim());
        turn.put("metatron", Objects.requireNonNullElse(metatronText, "").trim());
        turns.add(turn);
        while (turns.size() > MAX_STORED_TURNS) turns.remove(0);
        write(conversationId, turns);
    }

    private static String renderRecent(ArrayNode turns, int maxTurns, int maxChars) {
        StringBuilder out = new StringBuilder();
        int start = Math.max(0, turns.size() - Math.max(1, maxTurns));
        for (int i = start; i < turns.size(); i++) {
            appendTurn(out, turns.get(i), maxChars);
            if (out.length() >= maxChars) break;
        }
        return out.toString().trim();
    }

    private static void appendTurn(StringBuilder out, JsonNode turn, int maxChars) {
        append(out, "User", turn.path("human").asText(""), maxChars);
        append(out, "Metatron", turn.path("metatron").asText(""), maxChars);
    }

    private static void append(StringBuilder out, String role, String text, int maxChars) {
        if (text == null || text.isBlank() || out.length() >= maxChars) return;
        int remaining = maxChars - out.length();
        String value = text.length() <= remaining ? text : text.substring(0, remaining);
        out.append(role).append(": ").append(value).append('\n');
    }

    private static Set<String> tokens(String text) {
        Set<String> tokens = new HashSet<>();
        if (text == null || text.isBlank()) return tokens;
        for (String token : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_-]+")) {
            if (token.length() >= 3 && !STOP_WORDS.contains(token)) tokens.add(token);
        }
        return tokens;
    }

    private static int overlap(Set<String> left, Set<String> right) {
        int score = 0;
        for (String token : left) if (right.contains(token)) score++;
        return score;
    }

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "with", "this", "that", "from", "what", "when", "where", "how",
            "cái", "này", "đó", "với", "cho", "của", "một", "những", "được", "không", "thì", "làm",
            "phân", "tích", "tiếp", "tục", "please", "continue", "metatron");

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
            throw new IllegalStateException("conversation_memory_write_failed", failure);
        }
    }

    private Path file(String conversationId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(conversationId.getBytes(StandardCharsets.UTF_8));
            return root.resolve(HexFormat.of().formatHex(digest) + ".json");
        } catch (Exception impossible) {
            throw new IllegalStateException("conversation_memory_key_failed", impossible);
        }
    }

    private record ScoredTurn(int index, int score, JsonNode turn) {}
}
