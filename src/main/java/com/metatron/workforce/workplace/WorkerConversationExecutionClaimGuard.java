package com.metatron.workforce.workplace;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deterministic post-condition for live Worker conversation.
 *
 * Cognition may discuss, plan, recommend or propose actions freely. Only unsupported first-person
 * claims that an action already happened or is actually underway are suppressed. A single bad
 * sentence must never erase an otherwise useful strategic/analytical answer.
 */
final class WorkerConversationExecutionClaimGuard {
    private static final Pattern EXPLICIT_NEGATION = Pattern.compile(
            "(?is)(?:\\b(?:i|we)\\b|\\b(?:tao|tôi|mình|chúng tôi|chúng ta)\\b).{0,48}"
                    + "(?:have not|haven't|did not|didn't|am not|are not|chưa|không).{0,96}"
                    + "(?:audit|review|execut|generat|deploy|fix|commit|push|writ|creat|delet|updat|modif|run|initiat|build|test|inspect|implement|"
                    + "triển khai|thực hiện|kiểm tra|tạo|sửa|xóa|chạy|đẩy|cập nhật)");

    /*
     * Deliberately narrower than the old broad "I ... am/now ... start..." matcher.
     * Discourse such as "I am starting with demand planning" or "I will create a plan" is not
     * execution and must remain usable. These patterns target completed/ongoing effects.
     */
    private static final Pattern FIRST_PERSON_EXECUTION = Pattern.compile(
            "(?is)(?:"
                    + "\\b(?:i|we)\\s+(?:have|has)\\s+(?:already\\s+)?"
                    + "(?:audited|reviewed|executed|generated|deployed|fixed|committed|pushed|written|created|deleted|updated|modified|run|initiated|built|tested|inspected|implemented)\\b"
                    + "|\\b(?:i\\s+am|we\\s+are)\\s+(?:(?:now|currently|already)\\s+)?"
                    + "(?:auditing|reviewing|executing|generating|deploying|fixing|committing|pushing|writing|creating|deleting|updating|modifying|running|initiating|building|testing|inspecting|implementing)\\b"
                    + "|\\b(?:i|we)\\s+(?:already\\s+)?"
                    + "(?:audited|reviewed|executed|deployed|fixed|committed|pushed|created|deleted|updated|modified|initiated|built|tested|inspected|implemented)\\b"
                    + "|\\b(?:tao|tôi|mình|chúng tôi|chúng ta)\\b.{0,24}(?:đã|đang).{0,36}"
                    + "(?:audit|review|execute|deploy|fix|commit|push|write|create|delete|update|modify|run|initiate|build|test|inspect|implement|"
                    + "triển khai|thực hiện|kiểm tra|tạo|sửa|xóa|chạy|đẩy|cập nhật)"
                    + ")");

    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.!?])\\s+");

    private WorkerConversationExecutionClaimGuard() {}

    static String enforce(String text, List<String> evidenceReferences) {
        if (text == null || text.isBlank()) return text;
        if (hasSuccessfulExecutionEvidence(evidenceReferences)) return text;
        if (!FIRST_PERSON_EXECUTION.matcher(text).find()) return text;

        List<String> kept = new ArrayList<>();
        boolean suppressed = false;
        for (String line : text.split("\\R", -1)) {
            if (line.isBlank()) {
                kept.add("");
                continue;
            }
            String[] sentences = SENTENCE_SPLIT.split(line);
            List<String> safeSentences = new ArrayList<>();
            for (String sentence : sentences) {
                String candidate = sentence.trim();
                if (candidate.isBlank()) continue;
                boolean unsupportedClaim = FIRST_PERSON_EXECUTION.matcher(candidate).find()
                        && !EXPLICIT_NEGATION.matcher(candidate).find();
                if (unsupportedClaim) {
                    suppressed = true;
                    continue;
                }
                safeSentences.add(candidate);
            }
            if (!safeSentences.isEmpty()) kept.add(String.join(" ", safeSentences));
        }

        if (!suppressed) return text;

        String preserved = String.join("\n", kept).trim();
        String correction = "Execution note: no durable receipt/evidence proves the suppressed action claim. "
                + "That action remains proposed until it passes authority/authorization, Execution, and Observation/evidence.";

        // Preserve strategic reasoning, plans, recommendations and other useful content.
        return preserved.isBlank() ? correction : preserved + "\n\n" + correction;
    }

    static boolean hasSuccessfulExecutionEvidence(List<String> refs) {
        if (refs == null) return false;
        List<String> normalized = refs.stream()
                .filter(ref -> ref != null && !ref.isBlank())
                .map(ref -> ref.toLowerCase(Locale.ROOT))
                .toList();

        boolean successfulActionReceipt = normalized.stream()
                .anyMatch(ref -> ref.startsWith("action-fabric:") && ref.contains(":success=true"));
        boolean explicitExecutionReceipt = normalized.stream()
                .anyMatch(ref -> ref.startsWith("execution-receipt:"));
        boolean executionIdentity = normalized.stream()
                .anyMatch(ref -> ref.startsWith("execution:"));
        boolean completedExecutionState = normalized.stream()
                .anyMatch(ref -> ref.endsWith(":completed")
                        || ref.contains(":state=completed")
                        || ref.startsWith("execution-state:completed"));
        boolean durableWorkOutcome = normalized.stream()
                .anyMatch(ref -> ref.startsWith("work-outcome:"));

        return successfulActionReceipt
                || explicitExecutionReceipt
                || (executionIdentity && completedExecutionState)
                || durableWorkOutcome;
    }
}
