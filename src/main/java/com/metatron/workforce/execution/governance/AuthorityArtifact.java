package com.metatron.workforce.execution.governance;

import java.time.Instant;
import java.util.Objects;

/** Exact identity of one authoritative artifact actually used for a governed decision. */
public record AuthorityArtifact(
        String authorityLevel,
        String repository,
        String artifactPath,
        String repositoryCommitSha,
        String contentHash,
        String status,
        String scope,
        Instant resolvedAt) {

    public AuthorityArtifact {
        authorityLevel = require(authorityLevel, "authorityLevel");
        repository = require(repository, "repository");
        artifactPath = require(artifactPath, "artifactPath");
        repositoryCommitSha = require(repositoryCommitSha, "repositoryCommitSha");
        contentHash = require(contentHash, "contentHash");
        status = require(status, "status");
        scope = require(scope, "scope");
        Objects.requireNonNull(resolvedAt, "resolvedAt");
        if (!repositoryCommitSha.matches("[0-9a-fA-F]{40}")) {
            throw new IllegalArgumentException("repositoryCommitSha must be an immutable Git SHA");
        }
        if (!contentHash.contains(":")) {
            throw new IllegalArgumentException("contentHash must include algorithm prefix");
        }
    }

    public String identity() {
        return authorityLevel + "|" + repository + "|" + artifactPath + "|"
                + repositoryCommitSha.toLowerCase() + "|" + contentHash.toLowerCase();
    }

    private static String require(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
