package com.metatron.workforce.interaction.intelligence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/** Filesystem-backed conversation depth preference with atomic replacement. */
public final class PersistentIntelligenceDepthPreferenceStore implements IntelligenceDepthPreferenceStore {
    private final Path root;

    public PersistentIntelligenceDepthPreferenceStore(Path root) {
        this.root = Objects.requireNonNull(root, "root");
        try { Files.createDirectories(root); }
        catch (IOException e) { throw new IllegalStateException("depth preference store unavailable", e); }
    }

    @Override
    public synchronized IntelligenceDepthContract get(String conversationId) {
        Path file = file(conversationId);
        if (!Files.exists(file)) return IntelligenceDepthContract.automatic();
        try {
            String value = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (value.isBlank() || "AUTO".equals(value)) return IntelligenceDepthContract.automatic();
            return IntelligenceDepthContract.selected(IntelligenceDepth.valueOf(value));
        } catch (Exception e) {
            throw new IllegalStateException("invalid depth preference for conversation", e);
        }
    }

    @Override
    public synchronized void set(String conversationId, IntelligenceDepth depth) {
        Objects.requireNonNull(depth, "depth");
        write(file(conversationId), depth.name());
    }

    @Override
    public synchronized void clear(String conversationId) {
        try { Files.deleteIfExists(file(conversationId)); }
        catch (IOException e) { throw new IllegalStateException("cannot clear depth preference", e); }
    }

    private void write(Path target, String value) {
        try {
            Files.createDirectories(root);
            Path temp = Files.createTempFile(root, ".depth-", ".tmp");
            Files.writeString(temp, value + "\n", StandardCharsets.UTF_8);
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) { throw new IllegalStateException("cannot persist depth preference", e); }
    }

    private Path file(String conversationId) {
        Objects.requireNonNull(conversationId, "conversationId");
        if (conversationId.isBlank()) throw new IllegalArgumentException("conversationId must not be blank");
        return root.resolve(java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(conversationId.getBytes(StandardCharsets.UTF_8)) + ".depth");
    }
}
