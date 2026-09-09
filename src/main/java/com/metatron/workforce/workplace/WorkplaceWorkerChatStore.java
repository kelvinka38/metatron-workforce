package com.metatron.workforce.workplace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Durable presentation-layer continuity for direct Human↔Worker Control Room conversations. */
@Service
public final class WorkplaceWorkerChatStore {
    private static final int MAX_TURNS = 200;
    private final ObjectMapper json;
    private final Path root;

    public WorkplaceWorkerChatStore(
            ObjectMapper json,
            @Value("${METATRON_WORKPLACE_WORKER_CHAT_DIR:/var/lib/metatron-workforce/workplace-worker-chat}") String root) {
        this.json = json;
        this.root = Path.of(root);
    }

    public synchronized List<ChatTurn> history(String workerId) {
        try {
            Path file = file(workerId);
            if (!Files.exists(file)) return List.of();
            List<PersistedTurn> stored = json.readValue(Files.readString(file, StandardCharsets.UTF_8),
                    new TypeReference<List<PersistedTurn>>() {});
            if (stored == null || stored.isEmpty()) return List.of();
            return stored.stream().map(turn -> new ChatTurn(
                    Instant.parse(turn.at()),
                    turn.human(),
                    turn.worker(),
                    turn.requestReference(),
                    turn.runtimeId(),
                    turn.evidenceReferences())).toList();
        } catch (Exception failure) {
            return List.of();
        }
    }

    public synchronized String context(String workerId, int maxTurns, int maxChars) {
        List<ChatTurn> turns = history(workerId);
        StringBuilder out = new StringBuilder();
        int start = Math.max(0, turns.size() - Math.max(1, maxTurns));
        for (int i = start; i < turns.size() && out.length() < maxChars; i++) {
            ChatTurn turn = turns.get(i);
            append(out, "Human", turn.human(), maxChars);
            append(out, "Worker", turn.worker(), maxChars);
        }
        return out.toString().trim();
    }

    public synchronized ChatTurn append(String workerId, String human, WorkerConversationGateway.Reply reply) {
        List<ChatTurn> turns = new ArrayList<>(history(workerId));
        ChatTurn turn = new ChatTurn(
                Instant.now(),
                human == null ? "" : human.trim(),
                reply.text(),
                reply.requestReference(),
                reply.runtimeId(),
                reply.evidenceReferences().stream().limit(30).toList());
        turns.add(turn);
        while (turns.size() > MAX_TURNS) turns.removeFirst();
        write(workerId, turns);
        return turn;
    }

    private void write(String workerId, List<ChatTurn> turns) {
        try {
            Files.createDirectories(root);
            Path target = file(workerId);
            Path temp = Files.createTempFile(root, "worker-chat-", ".tmp");
            List<PersistedTurn> stored = turns.stream().map(turn -> new PersistedTurn(
                    turn.at().toString(),
                    turn.human(),
                    turn.worker(),
                    turn.requestReference(),
                    turn.runtimeId(),
                    turn.evidenceReferences())).toList();
            Files.writeString(temp, json.writeValueAsString(stored), StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception failure) {
            throw new IllegalStateException("workplace_worker_chat_persist_failed", failure);
        }
    }

    private Path file(String workerId) {
        if (workerId == null || workerId.isBlank()) throw new IllegalArgumentException("workerId required");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(workerId.getBytes(StandardCharsets.UTF_8));
            return root.resolve(HexFormat.of().formatHex(digest) + ".json");
        } catch (Exception impossible) {
            throw new IllegalStateException("worker_chat_key_failed", impossible);
        }
    }

    private static void append(StringBuilder out, String role, String text, int maxChars) {
        if (text == null || text.isBlank() || out.length() >= maxChars) return;
        String value = text.trim();
        int remaining = maxChars - out.length();
        if (value.length() > remaining) value = value.substring(0, remaining);
        out.append(role).append(": ").append(value).append('\n');
    }

    private record PersistedTurn(
            String at, String human, String worker, String requestReference,
            String runtimeId, List<String> evidenceReferences) {
        private PersistedTurn {
            evidenceReferences = evidenceReferences == null ? List.of() : List.copyOf(evidenceReferences);
        }
    }

    public record ChatTurn(
            Instant at, String human, String worker, String requestReference,
            String runtimeId, List<String> evidenceReferences) {
        public ChatTurn {
            evidenceReferences = evidenceReferences == null ? List.of() : List.copyOf(evidenceReferences);
        }
    }
}
