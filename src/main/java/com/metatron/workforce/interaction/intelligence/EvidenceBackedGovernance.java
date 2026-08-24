package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmResponse;

import java.util.List;
import java.util.Objects;

/** BIOS-backed governance gate for the Workforce intelligence runtime. */
public final class EvidenceBackedGovernance implements IntelligenceGovernance {
    private final BiosConformanceValidator validator;

    public EvidenceBackedGovernance() {
        this(new BiosConformanceValidator());
    }

    public EvidenceBackedGovernance(BiosConformanceValidator validator) {
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    @Override
    public void validate(IntelligenceRequest request, List<LlmResponse> responses, String finalText) {
        validator.validate(request, responses, finalText);
    }
}
