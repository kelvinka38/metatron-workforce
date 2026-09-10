package com.metatron.workforce.execution.governance;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;

import java.time.Clock;
import java.util.Objects;

/** Issues derivation receipts only from institution-owned code after exact authority/work checks. */
public final class DerivationValidator {
    private final GovernanceStateStore store;
    private final Clock clock;

    public DerivationValidator(GovernanceStateStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public DerivationReceipt derive(String objectiveId, ExecutionWorkSpec work,
                                    AuthoritySnapshot snapshot, ConstraintBundle bundle,
                                    boolean contradictionDetected) {
        Objects.requireNonNull(work); Objects.requireNonNull(snapshot); Objects.requireNonNull(bundle);
        if (!objectiveId.equals(snapshot.objectiveId())) {
            throw new GovernanceDeniedException("DERIVATION_UNVERIFIED", "objective/authority snapshot mismatch");
        }
        if (!snapshot.digest().equals(bundle.authorityDigest())) {
            throw new GovernanceDeniedException("DERIVATION_UNVERIFIED", "constraint bundle authority mismatch");
        }
        DerivationReceipt.Classification classification;
        if (snapshot.conflictStatus() != AuthoritySnapshot.ConflictStatus.NONE || contradictionDetected) {
            classification = DerivationReceipt.Classification.CHANGE_PROPOSAL_REQUIRED;
        } else if (work.consequence() == ExecutionWorkSpec.Consequence.MUTATING && !work.verifiable()) {
            classification = DerivationReceipt.Classification.REJECTED;
        } else {
            classification = DerivationReceipt.Classification.DERIVED;
        }
        String workDigest = WorkDigests.digest(work);
        String material = objectiveId + "|" + work.stepId() + "|" + workDigest + "|" + snapshot.digest()
                + "|" + bundle.digest() + "|" + classification.name();
        DerivationReceipt receipt = new DerivationReceipt(
                "derivation:" + GovernanceDigests.sha256(material), objectiveId, work.stepId(), workDigest,
                snapshot.snapshotId(), snapshot.digest(), bundle.bundleId(), bundle.digest(), classification,
                clock.instant());
        store.saveDerivationReceipt(receipt);
        return receipt;
    }
}
