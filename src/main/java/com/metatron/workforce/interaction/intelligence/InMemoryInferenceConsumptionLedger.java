package com.metatron.workforce.interaction.intelligence;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Thread-safe process-local ledger used for tests and as a compatibility default. */
public final class InMemoryInferenceConsumptionLedger implements InferenceConsumptionLedger {
    private final CopyOnWriteArrayList<InferenceConsumptionRecord> entries = new CopyOnWriteArrayList<>();

    @Override public void record(InferenceConsumptionRecord record) { entries.add(record); }
    @Override public List<InferenceConsumptionRecord> records() { return List.copyOf(entries); }
}
