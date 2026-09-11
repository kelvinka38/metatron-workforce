package com.metatron.workforce.interaction.intelligence;

/** Test/local routing-feedback store. */
public final class InMemoryIntelligenceRoutingFeedbackStore implements IntelligenceRoutingFeedbackStore {
    private Snapshot snapshot = Snapshot.empty();

    @Override
    public synchronized Snapshot load() { return snapshot; }

    @Override
    public synchronized void save(Snapshot snapshot) { this.snapshot = snapshot; }
}
