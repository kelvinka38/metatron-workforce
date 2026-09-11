package com.metatron.workforce.runtime.execution;

import com.metatron.workforce.runtime.CanonicalRepositoryScope;

import java.util.Objects;

/** One immutable-base repository component materialized inside an ExecutionAttempt workspace. */
public record ExecutionRepositoryComponent(
        String componentId,
        String repository,
        String requestedRef,
        String baseSha,
        String branchRef,
        String relativePath,
        Status status,
        String localBaselineSha,
        String localHeadSha,
        String pullRequestRef) {

    public enum Status { DECLARED, MATERIALIZING, READY, DIRTY, COMMITTED, PROPOSED, SEALED }

    public ExecutionRepositoryComponent {
        componentId = require(componentId, "componentId");
        if (!componentId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")) throw new IllegalArgumentException("invalid componentId");
        repository = CanonicalRepositoryScope.requireAllowed(require(repository, "repository"));
        requestedRef = require(requestedRef, "requestedRef");
        baseSha = clean(baseSha);
        if (!baseSha.isEmpty() && !baseSha.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("baseSha must be immutable 40-char SHA");
        branchRef = clean(branchRef);
        relativePath = require(relativePath, "relativePath");
        if (relativePath.startsWith("/") || relativePath.contains("..")) throw new IllegalArgumentException("unsafe relativePath");
        Objects.requireNonNull(status, "status");
        localBaselineSha = clean(localBaselineSha);
        localHeadSha = clean(localHeadSha);
        pullRequestRef = clean(pullRequestRef);
        if (!localBaselineSha.isEmpty() && !localBaselineSha.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("localBaselineSha invalid");
        if (!localHeadSha.isEmpty() && !localHeadSha.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("localHeadSha invalid");
    }

    public ExecutionRepositoryComponent withMaterialized(String resolvedBaseSha, String baselineSha) {
        String sha = require(resolvedBaseSha, "resolvedBaseSha").toLowerCase();
        String baseline = require(baselineSha, "baselineSha").toLowerCase();
        return new ExecutionRepositoryComponent(componentId, repository, requestedRef, sha, branchRef, relativePath,
                Status.READY, baseline, baseline, pullRequestRef);
    }

    public ExecutionRepositoryComponent withStatus(Status next) {
        return new ExecutionRepositoryComponent(componentId, repository, requestedRef, baseSha, branchRef, relativePath,
                next, localBaselineSha, localHeadSha, pullRequestRef);
    }

    public ExecutionRepositoryComponent withCommitted(String headSha) {
        return new ExecutionRepositoryComponent(componentId, repository, requestedRef, baseSha, branchRef, relativePath,
                Status.COMMITTED, localBaselineSha, require(headSha, "headSha").toLowerCase(), pullRequestRef);
    }

    public ExecutionRepositoryComponent withProposal(String prRef) {
        return new ExecutionRepositoryComponent(componentId, repository, requestedRef, baseSha, branchRef, relativePath,
                Status.PROPOSED, localBaselineSha, localHeadSha, require(prRef, "prRef"));
    }

    private static String require(String value, String field) {
        String v = clean(value);
        if (v.isEmpty()) throw new IllegalArgumentException(field + " required");
        return v;
    }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
