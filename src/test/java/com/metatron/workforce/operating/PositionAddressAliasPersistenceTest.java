package com.metatron.workforce.operating;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.metatron.workforce.management.AquacultureDomainPlanningCapability;
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
 * The additive Position contract fields (addressAliases, primaryCapability) persist with the constitution, a
 * contract written before they existed loads with them empty, and invalid declarations are rejected at the source.
 * Backfill/conflict behavior at start-up is covered by PositionContractGuardTest.
 */
class PositionAddressAliasPersistenceTest {
    private static final Instant AT = Instant.parse("2026-09-26T00:00:00Z");
    private static final String POSITION = AquacultureHeadAppointmentCapability.POSITION_REF;
    private static final PositionRouteCatalog ROUTED = PositionRouteCatalog.of(List.of(AquacultureDomainPlanningCapability.CAPABILITY));

    @TempDir Path temp;

    @Test
    void additiveFieldsSurviveRestart() {
        Path state = temp.resolve("constitution.json");
        new WorkerConstitutionService(new FileWorkerConstitutionStateStore(state), ROUTED)
                .ensureConstitution(new AquacultureHeadStaffingPolicy(), AT);
        WorkerConstitutionService.PositionOperatingContract reloaded =
                new WorkerConstitutionService(new FileWorkerConstitutionStateStore(state), ROUTED)
                        .contractForPosition(POSITION).orElseThrow();
        assertEquals(AquacultureHeadStaffingPolicy.ADDRESS_ALIASES, reloaded.addressAliases());
        assertEquals(AquacultureDomainPlanningCapability.CAPABILITY, reloaded.primaryCapability());
    }

    @Test
    void contractWrittenBeforeAdditiveFieldsLoadsWithThemEmpty() throws Exception {
        Path state = temp.resolve("constitution.json");
        new WorkerConstitutionService(new FileWorkerConstitutionStateStore(state), ROUTED)
                .ensureConstitution(new AquacultureHeadStaffingPolicy(), AT);
        ObjectMapper json = new ObjectMapper();
        ObjectNode root = (ObjectNode) json.readTree(state.toFile());
        ((ArrayNode) root.get("positionContracts")).forEach(node -> {
            ((ObjectNode) node).remove("addressAliases");
            ((ObjectNode) node).remove("primaryCapability");
        });
        json.writeValue(state.toFile(), root);

        WorkerConstitutionService.PositionOperatingContract legacy =
                new WorkerConstitutionService(new FileWorkerConstitutionStateStore(state), ROUTED)
                        .contractForPosition(POSITION).orElseThrow();
        assertEquals(List.of(), legacy.addressAliases());
        assertEquals("", legacy.primaryCapability());
    }

    @Test
    void invalidAddressDeclarationsAreRejected() {
        AutonomousStaffingPolicy.PositionContractSpec spec = new AquacultureHeadStaffingPolicy().positionContractSpec();
        String primary = AquacultureDomainPlanningCapability.CAPABILITY;
        for (List<String> invalid : List.of(List.of("WORKER-HEAD-OF-AQUACULTURE"), List.of("role-x"),
                List.of("HO:A"), List.of("HOA", "hoa"), List.of(" "))) {
            assertThrows(IllegalArgumentException.class, () -> spec.withAddress(invalid, primary), invalid.toString());
        }
        assertThrows(IllegalArgumentException.class, () -> spec.withAddress(List.of("HOA"), ""),
                "aliases require a primary capability");
        assertThrows(IllegalArgumentException.class, () -> spec.withAddress(List.of("HOA"), "fisheries.domain.planning"),
                "the primary capability must be one the Position requires");
        assertDoesNotThrow(() -> spec.withAddress(List.of(), ""), "a Position without aliases needs no primary capability");
    }
}
