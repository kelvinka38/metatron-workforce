package com.metatron.workforce.interaction.intelligence;

import java.util.List;

/** Institutional inference-usage evidence boundary. */
public interface InferenceConsumptionLedger {
    void record(InferenceConsumptionRecord record);
    List<InferenceConsumptionRecord> records();

    default long workerExternalPaidCount() {
        return records().stream()
                .filter(record -> record.originType() == IntelligenceOriginType.WORKER)
                .filter(record -> record.computeOwner() == IntelligenceComputeOwner.EXTERNAL_PAID)
                .count();
    }
}
