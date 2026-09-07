package com.metatron.workforce.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.intelligence.WorkerIntelligenceService;

import java.util.Objects;

/** Produces one stateful Intelligence-backed brain per governed Cognitive Worker execution. */
public final class GeneralCognitiveWorkerBrainFactory {
    private final WorkerIntelligenceService intelligence;
    private final ObjectMapper json;

    public GeneralCognitiveWorkerBrainFactory(WorkerIntelligenceService intelligence, ObjectMapper json) {
        this.intelligence = Objects.requireNonNull(intelligence, "intelligence");
        this.json = Objects.requireNonNull(json, "json");
    }

    public GeneralCognitiveWorkerBrain create() {
        return new GeneralCognitiveWorkerBrain(intelligence, json);
    }
}
