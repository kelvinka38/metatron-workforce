package com.metatron.workforce.operating;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.metatron.workforce.management.AquacultureHeadAppointmentCapability;
import com.metatron.workforce.management.AquacultureHeadStaffingPolicy;
import com.metatron.workforce.management.AutonomousStaffingPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Address aliases persist with the Position constitution, and a Position contract persisted before aliases existed
 * (production state after PR #543) takes the policy's aliases on the next reconciliation without a contract conflict.
 */
class PositionAddressAliasPersistenceTest {
    private static final Instant AT = Instant.parse("2026-09-26T00:00:00Z");
    private static final String POSITION = AquacultureHeadAppointmentCapability.POSITION_REF;

    @TempDir Path temp;

    @Test
    void aliasesSurviveRestart() {
        Path state = temp.resolve("constitution.json");
        new WorkerConstitutionService(new FileWorkerConstitutionStateStore(state))
                .ensureConstitution(new AquacultureHeadStaffingPolicy(), AT);
        assertEquals(AquacultureHeadStaffingPolicy.ADDRESS_ALIASES,
                new WorkerConstitutionService(new FileWorkerConstitutionStateStore(state))
                        .contractForPosition(POSITION).orElseThrow().addressAliases());
    }

    @Test
    void contractPersistedBeforeAliasesIsAmendedNotConflicted() throws Exception {
        Path state = temp.resolve("constitution.json");
        new WorkerConstitutionService(new FileWorkerConstitutionStateStore(state))
                .ensureConstitution(new AquacultureHeadStaffingPolicy(), AT);
        // Rewrite the file exactly as the pre-alias code wrote it: no addressAliases field at all.
        ObjectMapper json = new ObjectMapper();
        ObjectNode root = (ObjectNode) json.readTree(state.toFile());
        ((ArrayNode) root.get("positionContracts")).forEach(node -> ((ObjectNode) node).remove("addressAliases"));
        json.writeValue(state.toFile(), root);

        WorkerConstitutionService restarted = new WorkerConstitutionService(new FileWorkerConstitutionStateStore(state));
        WorkerConstitutionService.PositionOperatingContract legacy = restarted.contractForPosition(POSITION).orElseThrow();
        assertEquals(List.of(), legacy.addressAliases());

        assertDoesNotThrow(() -> restarted.ensureConstitution(new AquacultureHeadStaffingPolicy(), AT.plusSeconds(60)));
        WorkerConstitutionService.PositionOperatingContract amended = restarted.contractForPosition(POSITION).orElseThrow();
        assertEquals(AquacultureHeadStaffingPolicy.ADDRESS_ALIASES, amended.addressAliases());
        assertEquals(legacy.contractId(), amended.contractId());
        assertEquals(legacy.effectiveAt(), amended.effectiveAt());
        assertEquals(AquacultureHeadStaffingPolicy.ADDRESS_ALIASES,
                new WorkerConstitutionService(new FileWorkerConstitutionStateStore(state))
                        .contractForPosition(POSITION).orElseThrow().addressAliases());
    }

    @Test
    void aliasesStillCannotChangeTheStandingEnvelope() {
        WorkerConstitutionService constitution = WorkerConstitutionService.inMemory();
        constitution.ensureConstitution(new AquacultureHeadStaffingPolicy(), AT);
        // Same Position, different authority scopes: still a conflict, aliases or not.
        AutonomousStaffingPolicy conflicting = new AutonomousStaffingPolicy() {
            private final AquacultureHeadStaffingPolicy base = new AquacultureHeadStaffingPolicy();
            @Override public String capabilityRef() { return base.capabilityRef(); }
            @Override public FormationSpec formationSpec() { return base.formationSpec(); }
            @Override public List<CapabilityGrant> additionalCapabilities() { return base.additionalCapabilities(); }
            @Override public PositionContractSpec positionContractSpec() {
                PositionContractSpec spec = base.positionContractSpec();
                return new PositionContractSpec(spec.mission(), spec.responsibilities(), spec.reportingLines(),
                        spec.capabilityRequirements(), List.of("aquaculture:widened-envelope"), spec.resourceScopes(),
                        spec.escalationRoutes(), spec.successMeasures(), spec.decisionRights(), spec.operatingCoverage(),
                        spec.workingTimeZone(), spec.maxConcurrentAssignments(), List.of("HOA"));
            }
        };
        IllegalStateException conflict = assertThrows(IllegalStateException.class,
                () -> constitution.ensureConstitution(conflicting, AT));
        assertTrue(conflict.getMessage().startsWith("position-operating-contract-conflict:"), conflict.getMessage());
    }

    @Test
    void aliasesMayNotImpersonateExplicitAddressForms() {
        AutonomousStaffingPolicy.PositionContractSpec spec = new AquacultureHeadStaffingPolicy().positionContractSpec();
        for (List<String> invalid : List.of(List.of("WORKER-HEAD-OF-AQUACULTURE"), List.of("role-x"),
                List.of("HO:A"), List.of("HOA", "hoa"), List.of(" "))) {
            assertThrows(IllegalArgumentException.class, () -> spec.withAddressAliases(invalid), invalid.toString());
        }
    }
}
