package com.metatron.workforce.interaction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/** Filesystem-backed durable Chat/Meeting preference keyed by canonical Conversation id. */
public final class PersistentConversationSurfaceModeStore {
    private final Path root;

    public PersistentConversationSurfaceModeStore(Path root) {
        this.root = Objects.requireNonNull(root, "root");
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("conversation surface mode store unavailable", e);
        }
    }

    public synchronized ConversationSurfaceMode get(String conversationId) {
        Path file = file(conversationId);
        if (!Files.exists(file)) return ConversationSurfaceMode.CHAT;
        try {
            String value = Files.readString(file, StandardCharsets.UTF_8).trim();
            return value.isBlank() ? ConversationSurfaceMode.CHAT : ConversationSurfaceMode.valueOf(value);
        } catch (Exception e) {
            throw new IllegalStateException("invalid conversation surface mode", e);
        }
    }

    public synchronized void set(String conversationId, ConversationSurfaceMode mode) {
        Objects.requireNonNull(mode, "mode");
        write(file(conversationId), mode.name());
    }

    private void write(Path target, String value) {
        try {
            Files.createDirectories(root);
            Path temp = Files.createTempFile(root, ".surface-", ".tmp");
            Files.writeString(temp, value + "\n", StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot persist conversation surface mode", e);
        }
    }

    private Path file(String conversationId) {
        Objects.requireNonNull(conversationId, "conversationId");
        if (conversationId.isBlank()) throw new IllegalArgumentException("conversationId must not be blank");
        return root.resolve(java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(conversationId.getBytes(StandardCharsets.UTF_8)) + ".surface");
    }
}
