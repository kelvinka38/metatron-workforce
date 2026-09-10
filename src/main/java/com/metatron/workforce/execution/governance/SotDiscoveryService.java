package com.metatron.workforce.execution.governance;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Mandatory targeted authority discovery for consequential work. No LLM participates in validity decisions. */
public final class SotDiscoveryService {
    public record Result(SotDiscoveryRecord discovery, AuthoritySnapshot snapshot, ConstraintBundle constraints) {}

    private static final Set<String> INVALID_STATUS_TOKENS = Set.of("DRAFT", "REVOKED", "DEPRECATED", "UNKNOWN", "INVALID");

    private final AuthorityManifestCatalog catalog;
    private final GovernanceStateStore store;
    private final Clock clock;

    public SotDiscoveryService(AuthorityManifestCatalog catalog, GovernanceStateStore store, Clock clock) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Result discover(String objectiveId, String actorId, String taskType, String targetEntity) {
        AuthorityManifestCatalog.Manifest manifest = catalog.resolve(targetEntity);
        Instant at = clock.instant();
        List<AuthorityArtifact> artifacts = manifest.sources().stream().map(source -> source.resolve(at)).toList();
        for (AuthorityArtifact artifact : artifacts) validateStatus(artifact);
        Set<String> identities = artifacts.stream().map(AuthorityArtifact::identity).collect(java.util.stream.Collectors.toSet());
        for (ConstraintBinding constraint : manifest.constraints()) {
            if (!identities.contains(constraint.authorityArtifactIdentity())) {
                throw new GovernanceDeniedException("CONSTRAINT_PROVENANCE_INVALID",
                        constraint.constraintId() + " is not tied to discovered authority");
            }
        }
        List<String> constraintIds = manifest.constraints().stream().map(ConstraintBinding::constraintId).toList();
        AuthoritySnapshot snapshot = AuthoritySnapshot.create(objectiveId, targetEntity, manifest.targetScope(),
                artifacts, constraintIds, AuthoritySnapshot.ConflictStatus.NONE, at);
        ConstraintBundle bundle = ConstraintBundle.create(snapshot.digest(), manifest.constraints());
        SotDiscoveryRecord record = new SotDiscoveryRecord(
                "discovery:" + GovernanceDigests.sha256(objectiveId + "|" + actorId + "|" + taskType + "|" + snapshot.digest()),
                objectiveId, actorId, taskType, targetEntity, manifest.targetScope(),
                artifacts.stream().map(AuthorityArtifact::identity).toList(), constraintIds,
                artifacts.stream().map(a -> a.repository() + "@" + a.repositoryCommitSha() + ":" + a.artifactPath()).toList(),
                AuthoritySnapshot.ConflictStatus.NONE, true, at);

        // Persist all identity-bearing records before the caller can use the result.
        store.saveSnapshot(snapshot);
        store.saveConstraintBundle(bundle);
        store.saveDiscovery(record);
        store.setCurrentAuthorityDigest(targetEntity, snapshot.digest());
        return new Result(record, snapshot, bundle);
    }

    /** Current local indexed authority digest; no network/provider call on the hot execution path. */
    public String indexedAuthorityDigest(String objectiveId, String targetEntity) {
        AuthorityManifestCatalog.Manifest manifest = catalog.resolve(targetEntity);
        Instant at = clock.instant();
        List<AuthorityArtifact> artifacts = manifest.sources().stream().map(source -> source.resolve(at)).toList();
        return AuthoritySnapshot.create(objectiveId, targetEntity, manifest.targetScope(), artifacts,
                manifest.constraints().stream().map(ConstraintBinding::constraintId).toList(),
                AuthoritySnapshot.ConflictStatus.NONE, at).digest();
    }

    private static void validateStatus(AuthorityArtifact artifact) {
        String upper = artifact.status().toUpperCase(java.util.Locale.ROOT);
        if (INVALID_STATUS_TOKENS.stream().anyMatch(upper::contains)) {
            throw new GovernanceDeniedException("AUTHORITY_STATUS_INVALID",
                    artifact.repository() + ":" + artifact.artifactPath() + ":" + artifact.status());
        }
    }
}
