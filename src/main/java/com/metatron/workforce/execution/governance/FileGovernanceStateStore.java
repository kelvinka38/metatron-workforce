package com.metatron.workforce.execution.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Atomic JSON persistence following the existing Workforce runtime state-store pattern. */
public final class FileGovernanceStateStore implements GovernanceStateStore {
    static record State(
            Map<String, SotDiscoveryRecord> discoveries,
            Map<String, AuthoritySnapshot> snapshots,
            Map<String, ConstraintBundle> bundles,
            Map<String, DerivationReceipt> receipts,
            Map<String, ExecutionPlanBinding> plans,
            Map<String, String> activePlanByStep,
            Map<String, ExecutionAttemptGovernanceBinding> attemptBindings,
            Map<String, String> currentAuthority,
            List<GovernanceDenial> denials,
            List<CompletionDecision> completion) {
        State {
            discoveries = discoveries == null ? Map.of() : Map.copyOf(discoveries);
            snapshots = snapshots == null ? Map.of() : Map.copyOf(snapshots);
            bundles = bundles == null ? Map.of() : Map.copyOf(bundles);
            receipts = receipts == null ? Map.of() : Map.copyOf(receipts);
            plans = plans == null ? Map.of() : Map.copyOf(plans);
            activePlanByStep = activePlanByStep == null ? Map.of() : Map.copyOf(activePlanByStep);
            attemptBindings = attemptBindings == null ? Map.of() : Map.copyOf(attemptBindings);
            currentAuthority = currentAuthority == null ? Map.of() : Map.copyOf(currentAuthority);
            denials = denials == null ? List.of() : List.copyOf(denials);
            completion = completion == null ? List.of() : List.copyOf(completion);
        }
        static State empty() { return new State(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), List.of(), List.of()); }
    }

    private final Path path;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private State state;

    public FileGovernanceStateStore(Path path) {
        this.path = Objects.requireNonNull(path, "path");
        this.state = load();
    }

    private State load() {
        if (!Files.exists(path)) return State.empty();
        try { return mapper.readValue(path.toFile(), State.class); }
        catch (Exception failure) { throw new IllegalStateException("cannot load governance state:" + path, failure); }
    }

    @Override public synchronized void saveDiscovery(SotDiscoveryRecord record) {
        Map<String, SotDiscoveryRecord> next = new LinkedHashMap<>(state.discoveries()); next.put(record.discoveryId(), record);
        replace(new State(next, state.snapshots(), state.bundles(), state.receipts(), state.plans(), state.activePlanByStep(), state.attemptBindings(), state.currentAuthority(), state.denials(), state.completion()));
    }
    @Override public synchronized void saveSnapshot(AuthoritySnapshot snapshot) {
        Map<String, AuthoritySnapshot> next = new LinkedHashMap<>(state.snapshots()); next.put(snapshot.snapshotId(), snapshot);
        replace(new State(state.discoveries(), next, state.bundles(), state.receipts(), state.plans(), state.activePlanByStep(), state.attemptBindings(), state.currentAuthority(), state.denials(), state.completion()));
    }
    @Override public synchronized void saveConstraintBundle(ConstraintBundle bundle) {
        Map<String, ConstraintBundle> next = new LinkedHashMap<>(state.bundles()); next.put(bundle.bundleId(), bundle);
        replace(new State(state.discoveries(), state.snapshots(), next, state.receipts(), state.plans(), state.activePlanByStep(), state.attemptBindings(), state.currentAuthority(), state.denials(), state.completion()));
    }
    @Override public synchronized void saveDerivationReceipt(DerivationReceipt receipt) {
        Map<String, DerivationReceipt> next = new LinkedHashMap<>(state.receipts()); next.put(receipt.receiptId(), receipt);
        replace(new State(state.discoveries(), state.snapshots(), state.bundles(), next, state.plans(), state.activePlanByStep(), state.attemptBindings(), state.currentAuthority(), state.denials(), state.completion()));
    }
    @Override public synchronized void savePlanBinding(ExecutionPlanBinding plan) {
        Map<String, ExecutionPlanBinding> plans = new LinkedHashMap<>(state.plans()); plans.put(planKey(plan.planId(), plan.version()), plan);
        Map<String, String> active = new LinkedHashMap<>(state.activePlanByStep());
        for (String step : plan.steps().keySet()) {
            String stepKey = stepKey(plan.objectiveId(), step);
            if (plan.status() == ExecutionPlanBinding.Status.APPROVED) active.put(stepKey, planKey(plan.planId(), plan.version()));
            else active.remove(stepKey, planKey(plan.planId(), plan.version()));
        }
        replace(new State(state.discoveries(), state.snapshots(), state.bundles(), state.receipts(), plans, active, state.attemptBindings(), state.currentAuthority(), state.denials(), state.completion()));
    }
    @Override public synchronized void saveAttemptBinding(ExecutionAttemptGovernanceBinding binding) {
        Map<String, ExecutionAttemptGovernanceBinding> next = new LinkedHashMap<>(state.attemptBindings()); next.put(binding.attemptId(), binding);
        replace(new State(state.discoveries(), state.snapshots(), state.bundles(), state.receipts(), state.plans(), state.activePlanByStep(), next, state.currentAuthority(), state.denials(), state.completion()));
    }
    @Override public synchronized void saveDenial(GovernanceDenial denial) {
        List<GovernanceDenial> next = new ArrayList<>(state.denials()); next.add(denial);
        replace(new State(state.discoveries(), state.snapshots(), state.bundles(), state.receipts(), state.plans(), state.activePlanByStep(), state.attemptBindings(), state.currentAuthority(), next, state.completion()));
    }
    @Override public synchronized void saveCompletionDecision(CompletionDecision decision) {
        List<CompletionDecision> next = new ArrayList<>(state.completion()); next.add(decision);
        replace(new State(state.discoveries(), state.snapshots(), state.bundles(), state.receipts(), state.plans(), state.activePlanByStep(), state.attemptBindings(), state.currentAuthority(), state.denials(), next));
    }
    @Override public synchronized void setCurrentAuthorityDigest(String targetEntity, String digest) {
        Map<String, String> next = new LinkedHashMap<>(state.currentAuthority()); next.put(targetEntity, digest);
        replace(new State(state.discoveries(), state.snapshots(), state.bundles(), state.receipts(), state.plans(), state.activePlanByStep(), state.attemptBindings(), next, state.denials(), state.completion()));
    }

    @Override public synchronized Optional<SotDiscoveryRecord> discovery(String id) { return Optional.ofNullable(state.discoveries().get(id)); }
    @Override public synchronized Optional<AuthoritySnapshot> snapshot(String id) { return Optional.ofNullable(state.snapshots().get(id)); }
    @Override public synchronized Optional<ConstraintBundle> constraintBundle(String id) { return Optional.ofNullable(state.bundles().get(id)); }
    @Override public synchronized Optional<DerivationReceipt> derivationReceipt(String id) { return Optional.ofNullable(state.receipts().get(id)); }
    @Override public synchronized Optional<ExecutionPlanBinding> planBinding(String planId, int version) { return Optional.ofNullable(state.plans().get(planKey(planId, version))); }
    @Override public synchronized Optional<ExecutionPlanBinding> approvedPlanForStep(String objectiveId, String stepId) {
        String key = state.activePlanByStep().get(stepKey(objectiveId, stepId));
        return key == null ? Optional.empty() : Optional.ofNullable(state.plans().get(key)).filter(p -> p.status() == ExecutionPlanBinding.Status.APPROVED);
    }
    @Override public synchronized Optional<ExecutionAttemptGovernanceBinding> attemptBinding(String attemptId) { return Optional.ofNullable(state.attemptBindings().get(attemptId)); }
    @Override public synchronized Optional<String> currentAuthorityDigest(String targetEntity) { return Optional.ofNullable(state.currentAuthority().get(targetEntity)); }
    @Override public synchronized List<GovernanceDenial> denials() { return List.copyOf(state.denials()); }
    @Override public synchronized List<CompletionDecision> completionDecisions() { return List.copyOf(state.completion()); }

    private void replace(State next) {
        try {
            Path parent = path.toAbsolutePath().getParent(); if (parent != null) Files.createDirectories(parent);
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), next);
            try { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING); }
            state = next;
        } catch (Exception failure) {
            throw new IllegalStateException("cannot persist governance state:" + path, failure);
        }
    }

    private static String planKey(String id, int version) { return id + "@" + version; }
    private static String stepKey(String objectiveId, String stepId) { return objectiveId + "#" + stepId; }
}
