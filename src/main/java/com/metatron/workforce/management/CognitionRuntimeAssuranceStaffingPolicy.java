package com.metatron.workforce.management;

import org.springframework.stereotype.Component;

/** Reuses the governed General Engineering Worker identity for bounded cognition-runtime assurance. */
@Component
public final class CognitionRuntimeAssuranceStaffingPolicy implements AutonomousStaffingPolicy {
    private final GeneralEngineeringStaffingPolicy general = new GeneralEngineeringStaffingPolicy();

    @Override public String capabilityRef() { return CognitionRuntimeAssuranceCapability.CAPABILITY; }
    @Override public FormationSpec formationSpec() { return general.formationSpec(); }
    @Override public PositionContractSpec positionContractSpec() { return general.positionContractSpec(); }
}
