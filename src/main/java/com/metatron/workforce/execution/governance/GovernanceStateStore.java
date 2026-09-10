package com.metatron.workforce.execution.governance;

import java.util.List;
import java.util.Optional;

/** Durable source for enforcement records. Implementations must persist before acknowledging approval/completion. */
public interface GovernanceStateStore {
    void saveDiscovery(SotDiscoveryRecord record);
    void saveSnapshot(AuthoritySnapshot snapshot);
    void saveConstraintBundle(ConstraintBundle bundle);
    void saveDerivationReceipt(DerivationReceipt receipt);
    void savePlanBinding(ExecutionPlanBinding plan);
    void saveDenial(GovernanceDenial denial);
    void saveCompletionDecision(CompletionDecision decision);
    void setCurrentAuthorityDigest(String targetEntity, String digest);

    Optional<SotDiscoveryRecord> discovery(String id);
    Optional<AuthoritySnapshot> snapshot(String id);
    Optional<ConstraintBundle> constraintBundle(String id);
    Optional<DerivationReceipt> derivationReceipt(String id);
    Optional<ExecutionPlanBinding> planBinding(String planId, int version);
    Optional<ExecutionPlanBinding> approvedPlanForStep(String objectiveId, String stepId);
    Optional<String> currentAuthorityDigest(String targetEntity);
    List<GovernanceDenial> denials();
    List<CompletionDecision> completionDecisions();
}
