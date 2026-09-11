package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/** Durable filesystem-backed Cognitive Artifact store owned by Metatron. */
public final class PersistentCognitiveArtifactStore implements CognitiveArtifactStore {
    private final Path root;
    private final ObjectMapper objectMapper;

    public PersistentCognitiveArtifactStore(Path root, ObjectMapper objectMapper) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public synchronized Optional<CognitiveArtifact> findReusable(String inputFingerprint, Instant at) {
        requireNonBlank(inputFingerprint, "inputFingerprint");
        Objects.requireNonNull(at, "at");
        Path target = fileFor(inputFingerprint);
        try {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
                return Optional.empty();
            }
            CognitiveArtifact artifact = objectMapper.readValue(
                    Files.readString(target, StandardCharsets.UTF_8), CognitiveArtifact.class);
            if (!inputFingerprint.equals(artifact.inputFingerprint())) {
                throw new IllegalStateException("cognitive_artifact_fingerprint_mismatch");
            }
            return artifact.validAt(at) ? Optional.of(artifact) : Optional.empty();
        } catch (IOException failure) {
            throw new IllegalStateException("cognitive_artifact_read_failed", failure);
        }
    }

    @Override
    public synchronized void save(CognitiveArtifact artifact) {
        Objects.requireNonNull(artifact, "artifact");
        Path target = fileFor(artifact.inputFingerprint());
        try {
            Files.createDirectories(root);
            Path temp = Files.createTempFile(root, ".cognitive-artifact-", ".tmp");
            Files.writeString(temp, objectMapper.writeValueAsString(artifact), StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("cognitive_artifact_write_failed", failure);
        }
    }

    private Path fileFor(String fingerprint) {
        requireNonBlank(fingerprint, "fingerprint");
        return root.resolve(hash(fingerprint) + ".json").normalize();
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception impossible) {
            throw new IllegalStateException("cognitive_artifact_key_failed", impossible);
        }
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
