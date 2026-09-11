package com.metatron.workforce.interaction.intelligence;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe bounded-process implementation used by tests/migration composition. */
public final class InMemoryCognitiveArtifactStore implements CognitiveArtifactStore {
    private final Map<String, CognitiveArtifact> byFingerprint = new ConcurrentHashMap<>();

    @Override
    public Optional<CognitiveArtifact> findReusable(String inputFingerprint, Instant at) {
        Objects.requireNonNull(inputFingerprint, "inputFingerprint");
        Objects.requireNonNull(at, "at");
        CognitiveArtifact artifact = byFingerprint.get(inputFingerprint.trim());
        return artifact != null && artifact.validAt(at) ? Optional.of(artifact) : Optional.empty();
    }

    @Override
    public void save(CognitiveArtifact artifact) {
        Objects.requireNonNull(artifact, "artifact");
        byFingerprint.put(artifact.inputFingerprint(), artifact);
    }
}
