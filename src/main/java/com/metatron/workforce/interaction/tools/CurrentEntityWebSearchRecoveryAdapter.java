package com.metatron.workforce.interaction.tools;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Bounded recovery for fresh/current requirements about an entity relation or status.
 *
 * <p>Human/frontier phrasing often contains instruction verbs (for example Vietnamese
 * "xác định", "kiểm tra", "dựa trên") that are useful to the request but are not part of
 * the entity identity. Treating those words as subject tokens can admit lexically matching
 * dictionary pages while missing the requested entity. This adapter derives only the
 * relation/entity identity, retries a compact current query through the hardened web adapter,
 * and admits the result only when source evidence contains both the requested relation and
 * an entity token.</p>
 */
public final class CurrentEntityWebSearchRecoveryAdapter implements ToolAdapter {
    private static final Set<String> RELATION_TOKENS = Set.of(
            "president", "ceo", "chair", "chairman", "chairperson", "governor", "mayor",
            "minister", "leader", "head", "director", "secretary", "status", "owner");
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "with", "from", "this", "that", "what", "which", "who", "whom", "whose",
            "how", "about", "of", "to", "a", "an", "is", "are", "was", "were", "be", "being", "been",
            "current", "currently", "latest", "today", "now", "new", "fresh", "official", "officially",
            "data", "source", "sources", "use", "using", "check", "answer", "information", "external",
            "please", "provide", "provides", "providing", "cite", "determine", "identify", "find", "online",
            "tra", "cuu", "kiem", "dung", "su", "lieu", "moi", "nhat", "neu", "nguon", "cho", "bao", "nhieu",
            "khoang", "hien", "tai", "bay", "gio", "ngay", "luc", "nay", "nao", "va", "cua", "dang", "roi",
            "gi", "ai", "la", "xac", "dinh", "dua", "tren", "bang", "cach", "thong", "tin", "truc", "tuyen");

    private final ToolAdapter delegate;

    public CurrentEntityWebSearchRecoveryAdapter() {
        this(new WebSearchToolAdapter());
    }

    CurrentEntityWebSearchRecoveryAdapter(ToolAdapter delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        if (!WebSearchToolAdapter.CAPABILITY.equals(delegate.capability())) {
            throw new IllegalArgumentException("delegate must provide web.search");
        }
    }

    @Override
    public String capability() {
        return WebSearchToolAdapter.CAPABILITY;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        Objects.requireNonNull(request, "request");
        if (!WebSearchToolAdapter.CAPABILITY.equals(request.capability())) {
            return ToolResult.failure(request, "tool capability mismatch");
        }
        String query = request.input() == null ? "" : request.input().trim();
        if (!supports(query)) return ToolResult.failure(request, "current_entity_recovery_unsupported");

        Set<String> subject = subjectTokens(query);
        String compact = compactCurrentQuery(query, subject);
        if (compact.isBlank()) return ToolResult.failure(request, "current_entity_recovery_subject_missing");

        ToolRequest delegatedRequest = new ToolRequest(
                request.requestId(), request.requester(), request.capability(), request.target(), request.operation(),
                compact, request.authorityContext());
        ToolResult delegated;
        try {
            delegated = Objects.requireNonNull(delegate.execute(delegatedRequest), "delegated result");
        } catch (RuntimeException failure) {
            return ToolResult.failure(request, "current_entity_recovery_failed:" + failure.getClass().getSimpleName());
        }
        if (!request.requestId().equals(delegated.requestId())
                || !request.capability().equals(delegated.capability())) {
            return ToolResult.failure(request, "current_entity_recovery_attribution_mismatch");
        }
        if (!delegated.success()) {
            return ToolResult.failure(request, "current_entity_recovery_delegate_failed:" + delegated.output());
        }
        String corpus = evidenceCorpus(delegated);
        if (!subjectRelevant(subject, corpus)) {
            return ToolResult.failure(request, "current_entity_recovery_source_identity_rejected");
        }

        String output = "CURRENT ENTITY WEB RECOVERY\n"
                + "recovery_subject=" + orderedSubjectQuery(query, subject) + "\n"
                + "discovery_query=" + compact + "\n"
                + delegated.output();
        return new ToolResult(request.requestId(), request.capability(), request.target(), request.operation(),
                true, output, List.copyOf(delegated.evidenceReferences()));
    }

    static boolean supports(String value) {
        String folded = fold(value);
        if (folded.isBlank()) return false;
        if (folded.matches(".*\\b(version|versions|release|releases|lts)\\b.*")) return false;
        boolean fresh = folded.matches(".*\\b(current|currently|latest|today|now)\\b.*");
        if (!fresh) return false;
        Set<String> subject = subjectTokens(value);
        return subject.stream().anyMatch(RELATION_TOKENS::contains)
                && subject.stream().anyMatch(token -> !RELATION_TOKENS.contains(token));
    }

    static Set<String> subjectTokens(String value) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String token : fold(value).split("[^a-z0-9]+")) {
            if (token.length() < 3 || STOP_WORDS.contains(token)) continue;
            tokens.add(token);
        }
        return Set.copyOf(tokens);
    }

    static boolean subjectRelevant(Set<String> subject, String evidenceText) {
        if (subject == null || subject.isEmpty()) return false;
        Set<String> evidence = lexicalTokens(evidenceText);
        boolean relationMatched = subject.stream().filter(RELATION_TOKENS::contains).anyMatch(evidence::contains);
        boolean entityMatched = subject.stream().filter(token -> !RELATION_TOKENS.contains(token)).anyMatch(evidence::contains);
        if (subject.stream().anyMatch(RELATION_TOKENS::contains)
                && subject.stream().anyMatch(token -> !RELATION_TOKENS.contains(token))) {
            return relationMatched && entityMatched;
        }
        int overlap = 0;
        for (String token : subject) if (evidence.contains(token)) overlap++;
        return overlap >= (subject.size() == 1 ? 1 : Math.min(2, subject.size()));
    }

    static String compactCurrentQuery(String query, Set<String> subject) {
        String ordered = orderedSubjectQuery(query, subject);
        return ordered.isBlank() ? "" : "current " + ordered;
    }

    static String evidenceCorpus(ToolResult result) {
        String body = result.output() == null ? "" : result.output();
        String sourceOnly = body
                .replaceAll("(?im)^\\s*query=.*$", " ")
                .replaceAll("(?im)^\\s*answer=.*$", " ")
                .replaceAll("(?im)^\\s*search_queries=.*$", " ")
                .replaceAll("(?im)^\\s*subject_tokens=.*$", " ")
                .replaceAll("(?im)^\\s*recovery_subject=.*$", " ")
                .replaceAll("(?im)^\\s*discovery_query=.*$", " ");
        return sourceOnly + " " + String.join(" ", result.evidenceReferences());
    }

    private static String orderedSubjectQuery(String query, Set<String> subject) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        for (String token : fold(query).split("[^a-z0-9]+")) {
            if (subject.contains(token)) ordered.add(token);
        }
        return String.join(" ", ordered);
    }

    private static Set<String> lexicalTokens(String value) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String token : fold(value).split("[^a-z0-9]+")) {
            if (token.length() >= 3) tokens.add(token);
        }
        return tokens;
    }

    private static String fold(String value) {
        if (value == null || value.isBlank()) return "";
        String folded = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('đ', 'd')
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
        return folded
                .replaceAll("\\btong\\s+thong\\b", "president")
                .replaceAll("\\bhien\\s+tai\\b", "current")
                .replaceAll("\\bhom\\s+nay\\b", "today")
                .replaceAll("\\bxac\\s+dinh\\b", "determine")
                .replaceAll("\\bkiem\\s+tra\\b", "check")
                .replaceAll("\\bdua\\s+tren\\b", "using")
                .replaceAll("\\bbang\\s+cach\\b", "using")
                .replaceAll("\\bnguon\\s+thong\\s+tin\\b", "source")
                .replaceAll("\\bnguon\\s+truc\\s+tuyen\\b", "source")
                .replaceAll("\\btruc\\s+tuyen\\b", "online")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
