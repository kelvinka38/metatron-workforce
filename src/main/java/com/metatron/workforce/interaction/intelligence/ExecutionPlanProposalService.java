package com.metatron.workforce.interaction.intelligence;

import java.util.List;

/** Intelligence-owned reasoning service that proposes Work; Workforce remains the plan owner. */
@FunctionalInterface
public interface ExecutionPlanProposalService {
    List<ExecutionWorkSpec> propose(String caseId, NormalizedRequest request,
                                    List<String> availableExecutionCapabilities);
}
