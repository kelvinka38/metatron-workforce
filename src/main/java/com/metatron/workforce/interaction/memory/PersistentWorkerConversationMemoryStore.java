package com.metatron.workforce.interaction.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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

/**
 * Durable channel-neutral Human↔Worker memory.
 *
 * Identity is keyed by canonical Human + canonical Worker, never by Telegram chat id, browser
 * session, or provider channel. This makes one Worker remember the same topic across Workplace,
 * Telegram and future approved channels while keeping memories isolated between Workers.
 */
@Service
public final class PersistentWorkerConversationMemoryStore {
    private static final int MAX_STORED_TURNS = 500;

    private final ObjectMapper json;
    private final Path root;
    private final Path legacyWorkplaceRoot;

    @Autowired
    public PersistentWorkerConversationMemoryStore(
            ObjectMapper json,
            @Value("${METATRON_WORKER_CONVERSATION_MEMORY_PATH:/var/lib/metatron-workforce/worker-conversations}") String root,
            @Value("${METATRON_WORKPLACE_WORKER_CHAT_DIR:/var/lib/metatron-workforce/workplace-worker-chat}") String legacyWorkplaceRoot) {
        this.json = Objects.requireNonNull(json, "json");
        this.root = Path.of(Objects.requireNonNull(root, "root"));
        this.legacyWorkplaceRoot = Path.of(Objects.requireNonNull(legacyWorkplaceRoot, "legacyWorkplaceRoot"));
    }

    PersistentWorkerConversationMemoryStore(ObjectMapper json, Path root, Path legacyWorkplaceRoot) {
        this.json = Objects.requireNonNull(json, "json");
        this.root = Objects.requireNonNull(root, "root");
        this.legacyWorkplaceRoot = Objects.requireNonNull(legacyWorkplaceRoot, "legacyWorkplaceRoot");
    }

    public synchronized List<Turn> history(String humanId, String workerId) {
        validateIdentity(humanId, workerId);
        List<Turn> turns = read(humanId, workerId);
        if (!turns.isEmpty()) return List.copyOf(turns);

        // One-time compatibility read for the Control Room memory shipped before cross-channel
        // Worker memory. Only the single-owner canonical Human may inherit those historical turns.
        if ("human-primary".equals(humanId)) {
            List<Turn> legacy = readLegacyWorkplace(workerId);
            if (!legacy.isEmpty()) {
                write(humanId, workerId, legacy);
                return List.copyOf(legacy);
            }
        }
        return List.of();
    }

    public synchronized int turnCount(String humanId, String workerId) {
        return history(humanId, workerId).size();
    }

    public synchronized String contextFor(
            String humanId,
            String workerId,
            String currentText,
            int maxRecentTurns,
            int maxRelevantTurns,
            int maxChars) {
        List<Turn> turns = history(humanId, workerId);
        if (turns.isEmpty()) return "";

        int recentStart = Math.max(0, turns.size() - Math.max(1, maxRecentTurns));
        Set<String> queryTokens = tokens(currentText);
        List<ScoredTurn> relevant = new ArrayList<>();
        if (!queryTokens.isEmpty() && recentStart > 0 && maxRelevantTurns > 0) {
            for (int i = 0; i < recentStart; i++) {
                Turn turn = turns.get(i);
                int score = overlap(queryTokens, tokens(turn.human() + " " + turn.worker()));
                if (score > 0) relevant.add(new ScoredTurn(i, score, turn));
            }
            relevant.sort(Comparator.comparingInt(ScoredTurn::score).reversed()
                    .thenComparingInt(ScoredTurn::index).reversed());
            if (relevant.size() > maxRelevantTurns) {
                relevant = new ArrayList<>(relevant.subList(0, maxRelevantTurns));
            }
            relevant.sort(Comparator.comparingInt(ScoredTurn::index));
        }

        StringBuilder out = new StringBuilder();
        if (!relevant.isEmpty()) {
            out.append("RELEVANT OLDER WORKER MEMORY:\n");
            for (ScoredTurn item : relevant) {
                appendTurn(out, item.turn(), maxChars);
                if (out.length() >= maxChars) return out.toString().trim();
            }
            out.append("\nRECENT WORKER CONVERSATION:\n");
        }

        for (int i = recentStart; i < turns.size(); i++) {
            appendTurn(out, turns.get(i), maxChars);
            if (out.length() >= maxChars) break;
        }
        return out.toString().trim();
    }

    public synchronized Turn append(
            String humanId,
            String workerId,
            String channel,
            String humanText,
            String workerText,
            String requestReference,
            String runtimeId,
            List<String> evidenceReferences) {
        validateIdentity(humanId, workerId);
        List<Turn> turns = new ArrayList<>(history(humanId, workerId));
        Turn turn = new Turn(
                Instant.now(),
                humanId,
                workerId,
                normalizeChannel(channel),
                Objects.requireNonNullElse(humanText, "").trim(),
                Objects.requireNonNullElse(workerText, "").trim(),
                Objects.requireNonNullElse(requestReference, "").trim(),
                Objects.requireNonNullElse(runtimeId, "").trim(),
                evidenceReferences == null ? List.of() : evidenceReferences.stream()
                        .filter(Objects::nonNull).map(String::trim).filter(v -> !v.isBlank())
                        .distinct().limit(60).toList());
        turns.add(turn);
        while (turns.size() > MAX_STORED_TURNS) turns.removeFirst();
        write(humanId, workerId, turns);
        return turn;
    }

    private List<Turn> read(String humanId, String workerId) {
        try {
            Path file = file(humanId, workerId);
            if (!Files.exists(file)) return List.of();
            List<PersistedTurn> stored = json.readValue(
                    Files.readString(file, StandardCharsets.UTF_8),
                    new TypeReference<List<PersistedTurn>>() {});
            if (stored == null || stored.isEmpty()) return List.of();
            List<Turn> out = new ArrayList<>();
            for (PersistedTurn turn : stored) {
                out.add(new Turn(
                        Instant.parse(turn.at()),
                        humanId,
                        workerId,
                        normalizeChannel(turn.channel()),
                        Objects.requireNonNullElse(turn.human(), ""),
                        Objects.requireNonNullElse(turn.worker(), ""),
                        Objects.requireNonNullElse(turn.requestReference(), ""),
                        Objects.requireNonNullElse(turn.runtimeId(), ""),
                        turn.evidenceReferences() == null ? List.of() : List.copyOf(turn.evidenceReferences())));
            }
            return out;
        } catch (Exception failure) {
            return List.of();
        }
    }

    private List<Turn> readLegacyWorkplace(String workerId) {
        try {
            Path file = legacyFile(workerId);
            if (!Files.exists(file)) return List.of();
            List<LegacyTurn> stored = json.readValue(
                    Files.readString(file, StandardCharsets.UTF_8),
                    new TypeReference<List<LegacyTurn>>() {});
            if (stored == null || stored.isEmpty()) return List.of();
            return stored.stream().map(turn -> new Turn(
                    Instant.parse(turn.at()),
                    "human-primary",
                    workerId,
                    "workplace-legacy",
                    Objects.requireNonNullElse(turn.human(), ""),
                    Objects.requireNonNullElse(turn.worker(), ""),
                    Objects.requireNonNullElse(turn.requestReference(), ""),
                    Objects.requireNonNullElse(turn.runtimeId(), ""),
                    turn.evidenceReferences() == null ? List.of() : List.copyOf(turn.evidenceReferences())))
                    .toList();
        } catch (Exception failure) {
            return List.of();
        }
    }

    private void write(String humanId, String workerId, List<Turn> turns) {
        try {
            Files.createDirectories(root);
            Path target = file(humanId, workerId);
            Path temp = Files.createTempFile(root, "worker-conversation-", ".tmp");
            List<PersistedTurn> stored = turns.stream().map(turn -> new PersistedTurn(
                    turn.at().toString(), turn.channel(), turn.human(), turn.worker(),
                    turn.requestReference(), turn.runtimeId(), turn.evidenceReferences())).toList();
            Files.writeString(temp, json.writeValueAsString(stored), StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception failure) {
            throw new IllegalStateException("worker_conversation_memory_write_failed", failure);
        }
    }

    private Path file(String humanId, String workerId) {
        return root.resolve(hash("human=" + humanId + "|worker=" + workerId) + ".json");
    }

    private Path legacyFile(String workerId) {
        return legacyWorkplaceRoot.resolve(hash(workerId) + ".json");
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception impossible) {
            throw new IllegalStateException("worker_conversation_memory_key_failed", impossible);
        }
    }

    private static void validateIdentity(String humanId, String workerId) {
        if (humanId == null || humanId.isBlank()) throw new IllegalArgumentException("humanId required");
        if (workerId == null || workerId.isBlank()) throw new IllegalArgumentException("workerId required");
    }

    private static String normalizeChannel(String channel) {
        String value = channel == null ? "" : channel.trim().toLowerCase(Locale.ROOT);
        return value.isBlank() ? "unspecified" : value;
    }

    private static void appendTurn(StringBuilder out, Turn turn, int maxChars) {
        append(out, "Human", turn.human(), maxChars);
        append(out, "Worker", turn.worker(), maxChars);
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
            "cai", "nay", "do", "voi", "cho", "cua", "mot", "nhung", "duoc", "khong", "thi", "lam",
            "please", "continue", "metatron", "worker");

    private record PersistedTurn(
            String at, String channel, String human, String worker,
            String requestReference, String runtimeId, List<String> evidenceReferences) {}

    private record LegacyTurn(
            String at, String human, String worker,
            String requestReference, String runtimeId, List<String> evidenceReferences) {}

    private record ScoredTurn(int index, int score, Turn turn) {}

    public record Turn(
            Instant at,
            String humanId,
            String workerId,
            String channel,
            String human,
            String worker,
            String requestReference,
            String runtimeId,
            List<String> evidenceReferences) {
        public Turn {
            evidenceReferences = evidenceReferences == null ? List.of() : List.copyOf(evidenceReferences);
        }
    }
}
