package com.metatron.workforce.interaction.tools;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Last-resort recovery for qualifier-sensitive version requirements.
 *
 * <p>The ordinary web adapter and semantic qualifier recovery are attempted first. If those paths
 * cannot produce admissible evidence, this adapter searches only the subject identity (for example
 * "python" instead of "Python latest stable version") through the already hardened
 * {@link WebSearchToolAdapter}. The returned evidence still has to contain both the subject and a
 * concrete version observation, and the surrounding {@link DefaultToolFabric} re-applies semantic
 * admission against the original request. This keeps the recovery domain-independent while avoiding
 * a single failing result URL aborting the whole version lookup.</p>
 */
public final class SubjectOnlyVersionWebSearchRecoveryAdapter implements ToolAdapter {
    private final ToolAdapter delegate;

    public SubjectOnlyVersionWebSearchRecoveryAdapter() {
        this(new WebSearchToolAdapter());
    }

    SubjectOnlyVersionWebSearchRecoveryAdapter(ToolAdapter delegate) {
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
        if (!supports(query)) {
            return ToolResult.failure(request, "subject_only_version_recovery_unsupported");
        }

        Set<String> subject = SemanticQualifierWebSearchRecoveryAdapter.subjectTokens(query);
        if (subject.isEmpty()) {
            return ToolResult.failure(request, "subject_only_version_recovery_subject_missing");
        }
        String subjectQuery = orderedSubjectQuery(query, subject);
        if (subjectQuery.isBlank()) {
            return ToolResult.failure(request, "subject_only_version_recovery_subject_missing");
        }

        ToolRequest delegatedRequest = new ToolRequest(
                request.requestId(),
                request.requester(),
                request.capability(),
                request.target(),
                request.operation(),
                subjectQuery,
                request.constraints());
        ToolResult delegated;
        try {
            delegated = Objects.requireNonNull(delegate.execute(delegatedRequest), "delegated result");
        } catch (RuntimeException failure) {
            return ToolResult.failure(request,
                    "subject_only_version_recovery_failed:" + failure.getClass().getSimpleName());
        }
        if (!request.requestId().equals(delegated.requestId())
                || !request.capability().equals(delegated.capability())) {
            return ToolResult.failure(request, "subject_only_version_recovery_attribution_mismatch");
        }
        if (!delegated.success()) {
            return ToolResult.failure(request, "subject_only_version_recovery_failed:" + delegated.output());
        }

        String corpus = delegated.output() + " " + String.join(" ", delegated.evidenceReferences());
        if (!SemanticQualifierWebSearchRecoveryAdapter.subjectRelevant(subject, corpus)) {
            return ToolResult.failure(request, "subject_only_version_recovery_subject_rejected");
        }
        if (!SemanticQualifierWebSearchRecoveryAdapter.answersVersionRequirement(corpus)) {
            return ToolResult.failure(request, "subject_only_version_recovery_answer_shape_rejected");
        }

        String output = "SUBJECT-ONLY VERSION WEB RECOVERY\n"
                + "recovery_subject=" + subjectQuery + "\n"
                + delegated.output();
        return new ToolResult(
                request.requestId(), request.capability(), request.target(), request.operation(), true,
                output, List.copyOf(delegated.evidenceReferences()));
    }

    private static boolean supports(String query) {
        String folded = fold(query);
        return folded.matches(".*\\b(version|versions|release|releases|lts)\\b.*")
                && folded.matches(".*\\b(stable|latest|current|currently|today|now)\\b.*");
    }

    private static String orderedSubjectQuery(String query, Set<String> subject) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        for (String token : fold(query).split("[^a-z0-9]+")) {
            if (subject.contains(token)) ordered.add(token);
        }
        return String.join(" ", ordered);
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
}
