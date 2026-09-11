package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.FounderDefinedWorkerFormationService;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic planning bridge for explicit work addressed to one canonical Founder-defined Worker.
 * It does not create Workers or authority; it only binds an already explicit Worker target to the
 * shared governed cognitive-work capability so Workforce can create the real Assignment/Execution.
 */
public final class FounderWorkerExecutionPlanProposalService implements ExecutionPlanProposalService {
    private static final Pattern WORKER_REF = Pattern.compile("(?i)\\bWORKER-[A-Z0-9._:-]+\\b");
    private final ExecutionPlanProposalService delegate;

    public FounderWorkerExecutionPlanProposalService(ExecutionPlanProposalService delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public List<ExecutionWorkSpec> propose(
            String caseId,
            NormalizedRequest request,
            List<String> availableExecutionCapabilities) {
        List<ExecutionWorkSpec> deterministic = explicitFounderWorkerWork(request, availableExecutionCapabilities);
        if (!deterministic.isEmpty() && request.explicitlyRequestedProvider() == null) return deterministic;
        return delegate.propose(caseId, request, availableExecutionCapabilities);
    }

    static List<ExecutionWorkSpec> explicitFounderWorkerWork(
            NormalizedRequest request,
            List<String> availableExecutionCapabilities) {
        if (request == null || request.mode() != IntelligenceMode.EXECUTION) return List.of();
        boolean available = availableExecutionCapabilities != null && availableExecutionCapabilities.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .anyMatch(FounderDefinedWorkerFormationService.COGNITIVE_CAPABILITY::equals);
        if (!available) return List.of();

        String workerId = workerRef(request.target());
        if (workerId.isBlank()) workerId = workerRef(request.objective());
        if (workerId.isBlank()) return List.of();

        String objective = request.objective().trim();
        if (objective.isBlank()) return List.of();
        return List.of(new ExecutionWorkSpec(
                "founder-worker-cognitive-work",
                objective,
                workerId,
                FounderDefinedWorkerFormationService.COGNITIVE_CAPABILITY,
                List.of(),
                ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of(
                        "canonical Worker " + workerId + " produces the requested cognitive work product",
                        "the work is executed under a real Workforce Assignment attributed to " + workerId),
                List.of(
                        "founder-worker-work-product durable cognitive work product",
                        "worker-assignment evidence attributed to " + workerId,
                        "worker-cognitive-request evidence")));
    }

    static String workerRef(String value) {
        Matcher matcher = WORKER_REF.matcher(value == null ? "" : value);
        return matcher.find() ? matcher.group().toUpperCase(Locale.ROOT) : "";
    }
}
