package com.metatron.workforce.interaction.tools;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Adapter-backed implementation used by the runtime while preserving the existing ToolFabric contract. */
public final class DefaultToolFabric {
    private static final Pattern VERSION_VALUE = Pattern.compile(
            "(?i)(?:^|[^a-z0-9])v?([0-9]{1,4}\\.[0-9]+(?:\\.[0-9]+){0,3}(?:[-+][a-z0-9.-]+)?)(?:$|[^a-z0-9])");
    private static final Set<String> VERSION_QUERY_STOP_WORDS = Set.of(
            "the", "and", "for", "with", "from", "this", "that", "what", "which", "about",
            "current", "currently", "latest", "today", "now", "new", "fresh", "official", "officially",
            "stable", "version", "versions", "release", "releases", "released", "build", "edition", "lts",
            "data", "source", "sources", "use", "using", "check", "answer", "information", "external",
            "please", "provide", "provides", "providing", "cite", "download", "downloads", "user", "users",
            "what", "which", "is", "are", "of", "to", "a", "an");

    private final List<ToolAdapter> adapters;

    public DefaultToolFabric(List<ToolAdapter> adapters) {
        Objects.requireNonNull(adapters, "adapters");
        List<ToolAdapter> configured = new ArrayList<>(adapters);
        boolean hasPrimaryWebSearch = configured.stream().anyMatch(WebSearchToolAdapter.class::isInstance);
        boolean hasSemanticRecovery = configured.stream().anyMatch(SemanticQualifierWebSearchRecoveryAdapter.class::isInstance);
        if (hasPrimaryWebSearch && !hasSemanticRecovery) {
            configured.add(new SemanticQualifierWebSearchRecoveryAdapter());
        }
        this.adapters = List.copyOf(configured);
    }

    public ToolResult execute(ToolRequest request) {
        Objects.requireNonNull(request, "request");
        List<ToolAdapter> candidates = adapters.stream()
                .filter(candidate -> candidate.capability().equals(request.capability()))
                .toList();
        if (candidates.isEmpty()) {
            throw new IllegalStateException("tool capability is not configured: " + request.capability());
        }

        List<String> failures = new ArrayList<>();
        for (ToolAdapter adapter : candidates) {
            ToolResult result = Objects.requireNonNull(adapter.execute(request), "tool result");
            validateAttribution(request, result);
            if (result.success()) {
                if (semanticEvidenceAdmissible(request, result)) return result;
                failures.add(adapter.getClass().getSimpleName() + "=semantic_evidence_rejected");
                continue;
            }
            failures.add(adapter.getClass().getSimpleName() + "=" + result.output());
        }

        if (candidates.size() == 1) {
            return ToolResult.failure(request, failures.getFirst().substring(failures.getFirst().indexOf('=') + 1));
        }
        return ToolResult.failure(request, "all_tool_adapters_failed:" + failures);
    }

    /**
     * Tool transport success is not semantic evidence success. For qualifier-sensitive version queries,
     * require source identity overlap and an answer-shaped version observation before the fabric admits
     * a successful result. A nominally successful but off-topic primary result therefore falls through
     * to the bounded semantic recovery adapter instead of contaminating Case evidence.
     */
    static boolean semanticEvidenceAdmissible(ToolRequest request, ToolResult result) {
        if (!WebSearchToolAdapter.CAPABILITY.equals(request.capability())) return true;
        String query = fold(request.input());
        if (!qualifierSensitiveVersionQuery(query)) return true;
        if (result.evidenceReferences().isEmpty()) return false;

        Set<String> subject = subjectTokens(query);
        if (subject.isEmpty()) return false;

        String evidenceBody = result.output()
                .replaceAll("(?im)^\\s*query=.*$", " ")
                .replaceAll("(?im)^\\s*subject_tokens=.*$", " ");
        String corpus = fold(evidenceBody + " " + String.join(" ", result.evidenceReferences()));
        Set<String> evidenceTokens = lexicalTokens(corpus);
        int overlap = 0;
        for (String token : subject) if (evidenceTokens.contains(token)) overlap++;
        int required = subject.size() == 1 ? 1 : Math.min(2, subject.size());
        return overlap >= required && VERSION_VALUE.matcher(corpus).find();
    }

    private static boolean qualifierSensitiveVersionQuery(String query) {
        return query.matches(".*\\b(version|versions|release|releases|lts)\\b.*")
                && query.matches(".*\\b(stable|latest|current|currently|today|now)\\b.*");
    }

    private static Set<String> subjectTokens(String query) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String token : query.split("[^a-z0-9]+")) {
            if (token.length() < 2 || VERSION_QUERY_STOP_WORDS.contains(token)) continue;
            tokens.add(token);
        }
        return Set.copyOf(tokens);
    }

    private static Set<String> lexicalTokens(String value) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String token : value.split("[^a-z0-9]+")) {
            if (token.length() >= 2) tokens.add(token);
        }
        return tokens;
    }

    private static String fold(String value) {
        if (value == null || value.isBlank()) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('đ', 'd')
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static void validateAttribution(ToolRequest request, ToolResult result) {
        if (!request.requestId().equals(result.requestId()) || !request.capability().equals(result.capability())) {
            throw new IllegalStateException("tool result attribution mismatch");
        }
    }
}
