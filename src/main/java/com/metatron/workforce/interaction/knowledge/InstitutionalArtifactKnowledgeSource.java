package com.metatron.workforce.interaction.knowledge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Read-only bounded retrieval over runtime institutional artifacts / Worker work products.
 * Returned material is evidence/context only; this source does not perform Knowledge admission
 * and does not upgrade an artifact into institutional Knowledge.
 */
public final class InstitutionalArtifactKnowledgeSource implements KnowledgeSource {
    private static final long MAX_FILE_BYTES = 1_000_000L;
    private static final int MAX_CANDIDATES = 256;
    private static final int MAX_CONTENT_CHARS = 24_000;
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("json", "txt", "md", "log", "yaml", "yml");

    private final Path root;

    public InstitutionalArtifactKnowledgeSource(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    @Override
    public String sourceId() {
        return "institutional.artifacts";
    }

    @Override
    public KnowledgeDocument retrieve(KnowledgeQuery query) {
        Objects.requireNonNull(query, "query");
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return null;

        Set<String> queryTokens = tokens(query.query() + " " + query.scope());
        if (queryTokens.isEmpty()) return null;

        try (var paths = Files.walk(root, 4)) {
            Candidate best = paths
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(this::allowed)
                    .limit(MAX_CANDIDATES)
                    .map(path -> candidate(path, queryTokens))
                    .filter(Objects::nonNull)
                    .max(Comparator.comparingInt(Candidate::score)
                            .thenComparing(Candidate::modifiedAt))
                    .orElse(null);

            if (best == null || best.score() <= 0) return null;
            Path relative = root.relativize(best.path());
            String evidenceRef = "institutional-artifact:" + relative.toString().replace('\\', '/');
            return new KnowledgeDocument(
                    sourceId() + ":" + evidenceRef,
                    sourceId(),
                    relative.toString(),
                    best.content(),
                    List.of(evidenceRef));
        } catch (IOException failure) {
            return null;
        }
    }

    private Candidate candidate(Path path, Set<String> queryTokens) {
        try {
            long size = Files.size(path);
            if (size <= 0 || size > MAX_FILE_BYTES) return null;
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (content.isBlank()) return null;
            int score = overlap(queryTokens, tokens(path.getFileName() + " " + content));
            if (score <= 0) return null;
            if (content.length() > MAX_CONTENT_CHARS) content = content.substring(0, MAX_CONTENT_CHARS);
            return new Candidate(path, score, content, Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant());
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean allowed(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) return false;
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return false;
        return ALLOWED_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static Set<String> tokens(String text) {
        Set<String> result = new HashSet<>();
        if (text == null || text.isBlank()) return result;
        for (String token : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_-]+")) {
            if (token.length() >= 3 && !STOP_WORDS.contains(token)) result.add(token);
        }
        return result;
    }

    private static int overlap(Set<String> left, Set<String> right) {
        int score = 0;
        for (String token : left) if (right.contains(token)) score++;
        return score;
    }

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "with", "this", "that", "from", "what", "when", "where", "how",
            "current", "required", "evidence", "information", "data", "analysis",
            "cái", "này", "đó", "với", "cho", "của", "một", "những", "được", "không", "thì", "làm",
            "phân", "tích", "hiện", "tại", "thông", "tin", "bằng", "chứng");

    private record Candidate(Path path, int score, String content, Instant modifiedAt) {}
}
