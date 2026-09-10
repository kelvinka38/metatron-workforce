package com.metatron.workforce.execution.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic local discovery index. The manifest is discovery metadata, never a competing SoT.
 * Normal execution uses this local immutable index and does not query GitHub or an LLM per action.
 */
public final class AuthorityManifestCatalog {
    public static final String DEFAULT_RESOURCE = "sot-enforcement-authority-manifests.json";

    public record Source(String authorityLevel, String repository, String artifactPath,
                         String repositoryCommitSha, String contentHash, String status, String scope) {
        public Source {
            require(authorityLevel, "authorityLevel"); require(repository, "repository");
            require(artifactPath, "artifactPath"); require(repositoryCommitSha, "repositoryCommitSha");
            require(contentHash, "contentHash"); require(status, "status"); require(scope, "scope");
        }
        AuthorityArtifact resolve(Instant at) {
            return new AuthorityArtifact(authorityLevel, repository, artifactPath, repositoryCommitSha,
                    contentHash, status, scope, at);
        }
    }

    public record Manifest(String manifestId, List<String> targetPatterns, String targetScope,
                           List<Source> sources, List<ConstraintBinding> constraints) {
        public Manifest {
            require(manifestId, "manifestId");
            targetPatterns = targetPatterns == null ? List.of() : targetPatterns.stream()
                    .filter(Objects::nonNull).map(String::trim).filter(v -> !v.isBlank()).distinct().toList();
            targetScope = require(targetScope, "targetScope");
            sources = sources == null ? List.of() : List.copyOf(sources);
            constraints = constraints == null ? List.of() : List.copyOf(constraints);
            if (targetPatterns.isEmpty()) throw new IllegalArgumentException("targetPatterns required");
            if (sources.isEmpty()) throw new IllegalArgumentException("authority sources required");
        }
        int matchSpecificity(String target) {
            return targetPatterns.stream().filter(pattern -> matches(pattern, target))
                    .mapToInt(AuthorityManifestCatalog::specificity).max().orElse(-1);
        }
    }

    private record Root(List<Manifest> manifests) {}

    private final List<Manifest> manifests;

    public AuthorityManifestCatalog(List<Manifest> manifests) {
        Objects.requireNonNull(manifests, "manifests");
        this.manifests = List.copyOf(manifests);
        if (this.manifests.isEmpty()) throw new IllegalArgumentException("authority manifests required");
    }

    public static AuthorityManifestCatalog classpath() {
        ClassPathResource resource = new ClassPathResource(DEFAULT_RESOURCE);
        if (!resource.exists()) throw new IllegalStateException("authority manifest resource missing:" + DEFAULT_RESOURCE);
        try (var input = resource.getInputStream()) {
            Root root = new ObjectMapper().readValue(input, Root.class);
            if (root == null || root.manifests() == null || root.manifests().isEmpty()) {
                throw new IllegalStateException("authority manifest catalog empty");
            }
            return new AuthorityManifestCatalog(root.manifests());
        } catch (IOException failure) {
            throw new IllegalStateException("cannot load authority manifest catalog", failure);
        }
    }

    public Manifest resolve(String target) {
        String normalized = require(target, "target");
        List<Manifest> matches = manifests.stream()
                .filter(manifest -> manifest.matchSpecificity(normalized) >= 0)
                .sorted(Comparator.comparingInt((Manifest m) -> m.matchSpecificity(normalized)).reversed())
                .toList();
        if (matches.isEmpty()) throw new GovernanceDeniedException("AUTHORITY_UNRESOLVED", "no authority manifest for target:" + normalized);
        int best = matches.getFirst().matchSpecificity(normalized);
        List<Manifest> bestMatches = matches.stream().filter(m -> m.matchSpecificity(normalized) == best).toList();
        if (bestMatches.size() != 1) {
            throw new GovernanceDeniedException("AUTHORITY_CONFLICT", "ambiguous authority manifests for target:" + normalized);
        }
        return bestMatches.getFirst();
    }

    public List<Manifest> manifests() { return manifests; }

    private static boolean matches(String pattern, String target) {
        String p = pattern.trim();
        if (p.endsWith("*")) return target.startsWith(p.substring(0, p.length() - 1));
        return target.equals(p);
    }

    private static int specificity(String pattern) {
        String p = pattern.trim();
        return p.endsWith("*") ? p.length() - 1 : p.length() + 10_000;
    }

    private static String require(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
