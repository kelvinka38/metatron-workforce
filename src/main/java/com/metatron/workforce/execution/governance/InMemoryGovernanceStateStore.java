package com.metatron.workforce.execution.governance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Deterministic in-memory store for tests and bounded non-production composition. */
public final class InMemoryGovernanceStateStore implements GovernanceStateStore {
    private final Map<String, SotDiscoveryRecord> discoveries = new LinkedHashMap<>();
    private final Map<String, AuthoritySnapshot> snapshots = new LinkedHashMap<>();
    private final Map<String, ConstraintBundle> bundles = new LinkedHashMap<>();
    private final Map<String, DerivationReceipt> receipts = new LinkedHashMap<>();
    private final Map<String, ExecutionPlanBinding> plans = new LinkedHashMap<>();
    private final Map<String, String> activePlanByStep = new LinkedHashMap<>();
    private final Map<String, String> currentAuthority = new LinkedHashMap<>();
    private final List<GovernanceDenial> denials = new ArrayList<>();
    private final List<CompletionDecision> completion = new ArrayList<>();

    @Override public synchronized void saveDiscovery(SotDiscoveryRecord record) { discoveries.put(record.discoveryId(), record); }
    @Override public synchronized void saveSnapshot(AuthoritySnapshot snapshot) { snapshots.put(snapshot.snapshotId(), snapshot); }
    @Override public synchronized void saveConstraintBundle(ConstraintBundle bundle) { bundles.put(bundle.bundleId(), bundle); }
    @Override public synchronized void saveDerivationReceipt(DerivationReceipt receipt) { receipts.put(receipt.receiptId(), receipt); }
    @Override public synchronized void savePlanBinding(ExecutionPlanBinding plan) {
        plans.put(planKey(plan.planId(), plan.version()), plan);
        if (plan.status() == ExecutionPlanBinding.Status.APPROVED) {
            plan.steps().keySet().forEach(step -> activePlanByStep.put(stepKey(plan.objectiveId(), step), planKey(plan.planId(), plan.version())));
        } else {
            plan.steps().keySet().forEach(step -> activePlanByStep.remove(stepKey(plan.objectiveId(), step), planKey(plan.planId(), plan.version())));
        }
    }
    @Override public synchronized void saveDenial(GovernanceDenial denial) { denials.add(denial); }
    @Override public synchronized void saveCompletionDecision(CompletionDecision decision) { completion.add(decision); }
    @Override public synchronized void setCurrentAuthorityDigest(String targetEntity, String digest) { currentAuthority.put(targetEntity, digest); }

    @Override public synchronized Optional<SotDiscoveryRecord> discovery(String id) { return Optional.ofNullable(discoveries.get(id)); }
    @Override public synchronized Optional<AuthoritySnapshot> snapshot(String id) { return Optional.ofNullable(snapshots.get(id)); }
    @Override public synchronized Optional<ConstraintBundle> constraintBundle(String id) { return Optional.ofNullable(bundles.get(id)); }
    @Override public synchronized Optional<DerivationReceipt> derivationReceipt(String id) { return Optional.ofNullable(receipts.get(id)); }
    @Override public synchronized Optional<ExecutionPlanBinding> planBinding(String planId, int version) { return Optional.ofNullable(plans.get(planKey(planId, version))); }
    @Override public synchronized Optional<ExecutionPlanBinding> approvedPlanForStep(String objectiveId, String stepId) {
        String key = activePlanByStep.get(stepKey(objectiveId, stepId));
        return key == null ? Optional.empty() : Optional.ofNullable(plans.get(key)).filter(p -> p.status() == ExecutionPlanBinding.Status.APPROVED);
    }
    @Override public synchronized Optional<String> currentAuthorityDigest(String targetEntity) { return Optional.ofNullable(currentAuthority.get(targetEntity)); }
    @Override public synchronized List<GovernanceDenial> denials() { return List.copyOf(denials); }
    @Override public synchronized List<CompletionDecision> completionDecisions() { return List.copyOf(completion); }

    private static String planKey(String id, int version) { return id + "@" + version; }
    private static String stepKey(String objectiveId, String stepId) { return objectiveId + "#" + stepId; }
}
