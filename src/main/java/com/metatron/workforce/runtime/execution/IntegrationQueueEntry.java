package com.metatron.workforce.runtime.execution;

import com.metatron.workforce.runtime.CanonicalRepositoryScope;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** One attempt-owned candidate waiting to mutate a protected repository ref. */
public record IntegrationQueueEntry(
        String entryId,
        String attemptId,
        long attemptFencingToken,
        String componentId,
        String repository,
        String expectedBaseSha,
        String candidateHeadSha,
        List<String> changedPaths,
        List<String> protectedResources,
        List<String> ciEvidence,
        Status status,
        String reason,
        String integrationLeaseId,
        long integrationResourceFence,
        String mergedSha,
        long stateVersion,
        Instant createdAt,
        Instant updatedAt) {

    public enum Status { QUEUED, INTEGRATING, MERGED, STALE_BASE, CONFLICTED, FAILED, CANCELLED }

    public IntegrationQueueEntry {
        entryId = require(entryId, "entryId");
        attemptId = require(attemptId, "attemptId");
        if (attemptFencingToken < 1) throw new IllegalArgumentException("attemptFencingToken must be positive");
        componentId = require(componentId, "componentId");
        repository = CanonicalRepositoryScope.requireAllowed(require(repository, "repository"));
        expectedBaseSha = sha(expectedBaseSha, "expectedBaseSha");
        candidateHeadSha = sha(candidateHeadSha, "candidateHeadSha");
        if (candidateHeadSha.equals(expectedBaseSha)) throw new IllegalArgumentException("candidate must differ from expected base");
        changedPaths = cleanList(changedPaths, "changedPaths", true);
        protectedResources = cleanList(protectedResources, "protectedResources", false);
        ciEvidence = cleanList(ciEvidence, "ciEvidence", true);
        Objects.requireNonNull(status, "status");
        reason = clean(reason);
        integrationLeaseId = clean(integrationLeaseId);
        if (integrationResourceFence < 0) throw new IllegalArgumentException("integrationResourceFence invalid");
        if (integrationLeaseId.isBlank() != (integrationResourceFence == 0)) {
            throw new IllegalArgumentException("integration lease identity/fence must be paired");
        }
        mergedSha = clean(mergedSha).toLowerCase();
        if (!mergedSha.isEmpty()) sha(mergedSha, "mergedSha");
        if (status == Status.MERGED && mergedSha.isBlank()) throw new IllegalArgumentException("merged entry requires mergedSha");
        if (stateVersion < 1) throw new IllegalArgumentException("stateVersion must be positive");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public boolean activeCandidate() { return status == Status.QUEUED || status == Status.INTEGRATING; }
    public boolean terminal() { return status == Status.MERGED || status == Status.STALE_BASE || status == Status.CONFLICTED || status == Status.FAILED || status == Status.CANCELLED; }

    public IntegrationQueueEntry transition(Status next, String nextReason, String leaseId, long resourceFence,
                                            String nextMergedSha, Instant at) {
        return new IntegrationQueueEntry(entryId, attemptId, attemptFencingToken, componentId, repository,
                expectedBaseSha, candidateHeadSha, changedPaths, protectedResources, ciEvidence,
                next, nextReason, leaseId, resourceFence, nextMergedSha, stateVersion + 1, createdAt, at);
    }

    private static List<String> cleanList(List<String> values, String field, boolean required) {
        List<String> out = values == null ? List.of() : values.stream().map(IntegrationQueueEntry::require).distinct().sorted().toList();
        if (required && out.isEmpty()) throw new IllegalArgumentException(field + " required");
        if (out.size() > 500) throw new IllegalArgumentException(field + " too large");
        return List.copyOf(out);
    }
    private static String sha(String value, String field) {
        String v = require(value, field).toLowerCase();
        if (!v.matches("[0-9a-f]{40}")) throw new IllegalArgumentException(field + " must be immutable 40-char SHA");
        return v;
    }
    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
        return value.trim();
    }
    private static String require(String value) { return require(value, "value"); }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
