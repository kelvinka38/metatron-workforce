package com.metatron.workforce.workplace;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deterministic post-condition for live Worker conversation.
 *
 * Cognition may discuss or propose actions, but first-person execution claims are suppressed unless
 * the supplied evidence contains a durable successful action/execution/observation reference.
 */
final class WorkerConversationExecutionClaimGuard {
    private static final Pattern FIRST_PERSON_EXECUTION = Pattern.compile(
            "(?is)(?:\\b(?:i|we)\\b|\\b(?:tao|tôi|mình|chúng tôi|chúng ta)\\b).{0,48}"
                    + "(?:\\b(?:have|has|am|are|was|were|now|already|currently|started|starting|initiating)\\b|(?:đã|đang)).{0,96}"
                    + "(?:audit|review|execut|generat|deploy|fix|commit|push|writ|creat|delet|updat|modif|run|start|initiat|build|test|inspect|implement|"
                    + "triển khai|thực hiện|kiểm tra|tạo|sửa|xóa|chạy|đẩy|cập nhật)");

    private WorkerConversationExecutionClaimGuard() {}

    static String enforce(String text, List<String> evidenceReferences) {
        if (text == null || text.isBlank()) return text;
        if (hasSuccessfulExecutionEvidence(evidenceReferences)) return text;
        if (!FIRST_PERSON_EXECUTION.matcher(text).find()) return text;

        return "Không có execution receipt / Observation evidence cho hành động đó trong context của Meeting này. "
                + "Vì vậy Worker không được claim là đã hoặc đang thực hiện. Trạng thái đúng hiện tại: "
                + "chỉ có thể phân tích/đề xuất; execution phải đi qua authority check → Execution/action → receipt → Observation/evidence.";
    }

    static boolean hasSuccessfulExecutionEvidence(List<String> refs) {
        if (refs == null) return false;
        return refs.stream().filter(ref -> ref != null && !ref.isBlank())
                .map(ref -> ref.toLowerCase(Locale.ROOT))
                .anyMatch(ref -> (ref.startsWith("action-fabric:") && ref.contains(":success=true"))
                        || ref.startsWith("execution-receipt:")
                        || ref.startsWith("execution:")
                        || ref.startsWith("work-outcome:")
                        || ref.startsWith("observation-report:"));
    }
}
