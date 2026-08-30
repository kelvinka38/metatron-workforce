package com.metatron.workforce.interaction.intelligence;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provider- and channel-neutral deterministic recognition for execution intents that are
 * unambiguous, read-only, and backed by a capability that actually exists in the Workforce
 * inventory. This is semantic normalization only: it creates no authority or authorization.
 */
final class DeterministicExecutionIntentResolver {
    static final String REPOSITORY_AUDIT_READ = "repository.audit.read";

    private static final Pattern REPOSITORY = Pattern.compile(
            "(?i)(?:github\\.com/)?([a-z0-9_.-]+/[a-z0-9_.-]+)");
    private static final Pattern AUDIT_INTENT = Pattern.compile(
            "(?i)\\b(audit|inspect|review)\\b|kiểm\\s*tra|kiem\\s*tra|rà\\s*soát|ra\\s*soat");
    private static final Pattern REPOSITORY_CONTEXT = Pattern.compile(
            "(?i)\\b(repository|repo|github)\\b");
    private static final Pattern READ_ONLY = Pattern.compile(
            "(?i)read[- ]?only|do\\s+not\\s+(mutate|modify|write|delete|change)|don't\\s+(mutate|modify|write|delete|change)|without\\s+(mutating|modifying|writing|deleting|changing)|chỉ\\s*đọc|chi\\s*doc|không\\s*(sửa|thay đổi|ghi|xóa)|khong\\s*(sua|thay doi|ghi|xoa)");
    private static final Pattern MUTATING = Pattern.compile(
            "(?i)\\b(fix|deploy|delete|remove|update|modify|write|push|commit|merge|mutate|change)\\b|\\b(sửa|xóa|ghi|triển khai|thay đổi)\\b");
    private static final Pattern PROHIBITION = Pattern.compile(
            "(?i)(do\\s+not|don't|without)\\s+(mutate|mutating|modify|modifying|write|writing|delete|deleting|change|changing)(?:\\s+anything)?|(?:không|khong)\\s*(?:sửa|sua|xóa|xoa|ghi|thay đổi|thay doi)");

    private DeterministicExecutionIntentResolver() {}

    static Optional<NormalizedRequest> resolve(String humanText, List<String> capabilityCatalog) {
        if (humanText == null || humanText.isBlank() || capabilityCatalog == null) return Optional.empty();
        Set<String> capabilities = Set.copyOf(capabilityCatalog);
        if (!capabilities.contains(REPOSITORY_AUDIT_READ)) return Optional.empty();

        String text = humanText.trim();
        if (!AUDIT_INTENT.matcher(text).find()) return Optional.empty();
        if (!REPOSITORY_CONTEXT.matcher(text).find() && !text.toLowerCase(Locale.ROOT).contains("github.com/")) {
            return Optional.empty();
        }
        if (!READ_ONLY.matcher(text).find()) return Optional.empty();

        String positiveIntent = PROHIBITION.matcher(text).replaceAll(" ");
        if (MUTATING.matcher(positiveIntent).find()) return Optional.empty();

        Matcher repository = REPOSITORY.matcher(text);
        if (!repository.find()) return Optional.empty();
        String target = repository.group(1);
        if (target.equalsIgnoreCase("github.com")) return Optional.empty();

        String objective = "Perform a governed read-only repository audit of " + target + " and return evidence";
        ExecutionWorkSpec step = new ExecutionWorkSpec(
                "repository-audit-1",
                objective,
                target,
                REPOSITORY_AUDIT_READ,
                List.of(),
                ExecutionWorkSpec.Consequence.READ_ONLY);

        return Optional.of(new NormalizedRequest(
                objective,
                target,
                List.of("READ_ONLY", "EVIDENCE_REQUIRED"),
                IntelligenceDepth.ANALYZE,
                "terminal execution result with evidence",
                List.of(),
                List.of("MUTATION"),
                "",
                "",
                IntelligenceMode.EXECUTION,
                CollaborationMode.SINGLE,
                List.of(AnalyticalProtocolType.AUDIT),
                DeterministicCapability.NONE,
                List.of(),
                List.of(step),
                false,
                null,
                null,
                ""));
    }
}
