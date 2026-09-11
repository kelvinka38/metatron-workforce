package com.metatron.workforce.interaction.intelligence;

import java.time.Instant;
import java.util.Optional;

/** Institutional store for provider-neutral reusable cognition. */
public interface CognitiveArtifactStore {
    Optional<CognitiveArtifact> findReusable(String inputFingerprint, Instant at);
    void save(CognitiveArtifact artifact);
}
