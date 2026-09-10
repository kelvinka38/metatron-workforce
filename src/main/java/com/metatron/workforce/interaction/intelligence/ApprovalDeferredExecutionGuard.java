package com.metatron.workforce.interaction.intelligence;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Conservative authorization gate for Human requests that explicitly defer execution until a later approval.
 *
 * <p>This is not an intent router. It never grants execution. It only prevents deterministic/semantic
 * execution admission when the Human has made approval a precondition for execution.</p>
 */
final class ApprovalDeferredExecutionGuard {
    private static final Pattern[] DEFERRED_APPROVAL = new Pattern[] {
            Pattern.compile("\\b(?:once|after|when|only after)\\s+(?:i|we|the human|the founder)?\\s*(?:approve|approved|approval)\\b"),
            Pattern.compile("\\b(?:execute|deploy|implement|apply|change|modify|create|provision|staff)\\b.{0,80}\\bafter\\s+(?:my|human|founder)?\\s*approval\\b"),
            Pattern.compile("\\b(?:wait|await)\\s+(?:for\\s+)?(?:my|human|founder)?\\s*approval\\b.{0,80}\\b(?:before|then)\\b"),
            Pattern.compile("\\bsau khi\\s+(?:toi|tao|minh|human|founder)?\\s*(?:duyet|approve)\\b"),
            Pattern.compile("\\b(?:chi|chỉ)\\s+(?:thuc thi|execute|deploy|implement|lam|làm)\\b.{0,80}\\b(?:sau khi|khi)\\b.{0,40}\\b(?:duyet|approve)\\b"),
            Pattern.compile("\\b(?:cho|doi|đợi|chờ)\\s+(?:toi|tao|minh)?\\s*(?:duyet|approve)\\b.{0,80}\\b(?:roi|rồi|thi|thì|moi|mới)\\b")
    };

    private ApprovalDeferredExecutionGuard() {}

    static boolean requiresApprovalBeforeExecution(String humanText) {
        String normalized = folded(humanText);
        if (normalized.isBlank()) return false;
        for (Pattern pattern : DEFERRED_APPROVAL) {
            if (pattern.matcher(normalized).find()) return true;
        }
        return false;
    }

    private static String folded(String value) {
        String source = value == null ? "" : value;
        return Normalizer.normalize(source, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }
}
