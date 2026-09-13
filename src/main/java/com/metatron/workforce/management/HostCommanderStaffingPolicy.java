package com.metatron.workforce.management;

import org.springframework.stereotype.Component;

/** Reuses the existing general engineering Worker identity/position for bounded Host Commander execution. */
@Component
public final class HostCommanderStaffingPolicy implements AutonomousStaffingPolicy {
    private final GeneralEngineeringStaffingPolicy general = new GeneralEngineeringStaffingPolicy();

    @Override public String capabilityRef() { return HostCommanderAutonomousCapability.CAPABILITY; }
    @Override public FormationSpec formationSpec() { return general.formationSpec(); }
    @Override public PositionContractSpec positionContractSpec() { return general.positionContractSpec(); }
}
