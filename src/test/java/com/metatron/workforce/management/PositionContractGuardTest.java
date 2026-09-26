package com.metatron.workforce.management;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.metatron.workforce.core.FileWorkforceCoreStateStore;
import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.operating.FileWorkerConstitutionStateStore;
import com.metatron.workforce.operating.PositionRouteCatalog;
import com.metatron.workforce.operating.WorkerConstitutionRuntimeMaterializer;
import com.metatron.workforce.operating.WorkerConstitutionService;
import com.metatron.workforce.runtime.RuntimeCapacityCoordinator;
import com.metatron.workforce.runtime.RuntimeRegistry;
import com.metatron.workforce.runtime.WorkerResourceScopeService;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * CTO decisions on PR #545, exercised through the real Head of Aquaculture boot reconciliation over durable state:
 * <ol>
 *   <li>Contract backfill: only the additive fields (addressAliases, primaryCapability) may be filled, and only while
 *       the stored value is empty; each backfill leaves "contract-backfill:&lt;field&gt;:&lt;workerId&gt;:&lt;value&gt;"
 *       evidence. A different stored value, or any other differing field, is a conflict and the Head is DEGRADED.</li>
 *   <li>primaryCapability is required when address aliases are declared and must have a registered route;
 *       otherwise start-up leaves the Head DEGRADED.</li>
 * </ol>
 */
class PositionContractGuardTest {
    private static final String HOA = AquacultureHeadAppointmentCapability.WORKER_ID;
    private static final String POSITION = AquacultureHeadAppointmentCapability.POSITION_REF;
    private static final PositionRouteCatalog ROUTED = PositionRouteCatalog.of(List.of(AquacultureDomainPlanningCapability.CAPABILITY));

    @TempDir Path temp;

    private HeadOfAquacultureBootstrapStatus boot(AutonomousStaffingPolicy policy, PositionRouteCatalog routes) throws Exception {
        WorkforceCoreService core = new WorkforceCoreService(new FileWorkforceCoreStateStore(temp.resolve("core.json")));
        WorkerRuntimeProfileBindingService profiles = new WorkerRuntimeProfileBindingService(temp.resolve("bindings.tsv"));
        WorkerResourceScopeService scopes = new WorkerResourceScopeService(temp.resolve("scopes.tsv"));
        WorkerConstitutionService constitution = new WorkerConstitutionService(
                new FileWorkerConstitutionStateStore(temp.resolve("constitution.json")), routes);
        AutonomousStaffingService staffing = new AutonomousStaffingService(core, List.of(policy), profiles, constitution, scopes);
        HeadOfAquacultureBootstrapStatus status = new HeadOfAquacultureBootstrapStatus();
        new LiveManagementConfiguration().headOfAquacultureReconciliation(
                staffing, new AquacultureHeadAppointmentCapability(core, profiles, scopes), core,
                new RuntimeCapacityCoordinator(new RuntimeRegistry()), mock(WorkerConstitutionRuntimeMaterializer.class),
                status, true).run(null);
        return status;
    }

    private WorkerConstitutionService.PositionOperatingContract stored() {
        return new WorkerConstitutionService(new FileWorkerConstitutionStateStore(temp.resolve("constitution.json")), ROUTED)
                .contractForPosition(POSITION).orElseThrow();
    }

    private void editStoredContract(Consumer<ObjectNode> edit) throws Exception {
        Path state = temp.resolve("constitution.json");
        ObjectMapper json = new ObjectMapper()
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        ObjectNode root = (ObjectNode) json.readTree(state.toFile());
        ((ArrayNode) root.get("positionContracts")).forEach(node -> {
            if (POSITION.equals(node.get("positionRef").asText())) edit.accept((ObjectNode) node);
        });
        json.writeValue(state.toFile(), root);
    }

    private static void assertDegraded(HeadOfAquacultureBootstrapStatus status, String reason) {
        assertEquals(HeadOfAquacultureBootstrapStatus.State.DEGRADED, status.snapshot().state(), status.snapshot().detail());
        assertTrue(status.snapshot().detail().contains(reason), status.snapshot().detail());
    }

    @Test
    void emptyStoredAdditiveFieldsAreBackfilledWithEvidence() throws Exception {
        assertEquals(HeadOfAquacultureBootstrapStatus.State.READY, boot(new AquacultureHeadStaffingPolicy(), ROUTED).snapshot().state());
        WorkerConstitutionService.PositionOperatingContract original = stored();
        assertTrue(original.evidenceReferences().stream().noneMatch(e -> e.startsWith("contract-backfill:")),
                "a freshly formed contract is not a backfill");
        // Exactly what production persisted before this change: neither additive field present.
        editStoredContract(node -> {
            node.remove("addressAliases");
            node.remove("primaryCapability");
        });

        HeadOfAquacultureBootstrapStatus restarted = boot(new AquacultureHeadStaffingPolicy(), ROUTED);
        assertEquals(HeadOfAquacultureBootstrapStatus.State.READY, restarted.snapshot().state(), restarted.snapshot().detail());
        WorkerConstitutionService.PositionOperatingContract backfilled = stored();
        assertEquals(List.of("HOA", "Head of Aquaculture"), backfilled.addressAliases());
        assertEquals(AquacultureDomainPlanningCapability.CAPABILITY, backfilled.primaryCapability());
        assertEquals(original.contractId(), backfilled.contractId());
        assertEquals(original.effectiveAt(), backfilled.effectiveAt());
        assertTrue(backfilled.evidenceReferences().contains(
                "contract-backfill:addressAliases:" + HOA + ":HOA,Head of Aquaculture"), backfilled.evidenceReferences().toString());
        assertTrue(backfilled.evidenceReferences().contains(
                "contract-backfill:primaryCapability:" + HOA + ":" + AquacultureDomainPlanningCapability.CAPABILITY),
                backfilled.evidenceReferences().toString());

        // Idempotent: a further restart neither conflicts nor records another backfill.
        assertEquals(HeadOfAquacultureBootstrapStatus.State.READY, boot(new AquacultureHeadStaffingPolicy(), ROUTED).snapshot().state());
        assertEquals(2, stored().evidenceReferences().stream().filter(e -> e.startsWith("contract-backfill:")).count());
    }

    @Test
    void differentStoredAdditiveValueIsAConflict() throws Exception {
        boot(new AquacultureHeadStaffingPolicy(), ROUTED);
        editStoredContract(node -> node.putArray("addressAliases").add("HOA-PREVIOUS"));
        assertDegraded(boot(new AquacultureHeadStaffingPolicy(), ROUTED), "position-operating-contract-conflict");

        editStoredContract(node -> {
            node.putArray("addressAliases").add("HOA").add("Head of Aquaculture");
            node.put("primaryCapability", "aquaculture.reporting");
        });
        assertDegraded(boot(new AquacultureHeadStaffingPolicy(), ROUTED), "position-operating-contract-conflict");
        assertEquals("aquaculture.reporting", stored().primaryCapability(), "a conflicting stored value is never overwritten");
    }

    @Test
    void differingNonAdditiveFieldIsAConflictEvenWhenAdditiveFieldsAreEmpty() throws Exception {
        boot(new AquacultureHeadStaffingPolicy(), ROUTED);
        editStoredContract(node -> {
            node.remove("addressAliases");
            node.remove("primaryCapability");
            node.put("mission", "A previously ratified, different mission.");
        });
        assertDegraded(boot(new AquacultureHeadStaffingPolicy(), ROUTED), "position-operating-contract-conflict");
        assertEquals(List.of(), stored().addressAliases(), "nothing is backfilled into a conflicting contract");
    }

    @Test
    void aliasesWithoutPrimaryCapabilityLeaveTheHeadDegraded() throws Exception {
        AquacultureHeadStaffingPolicy base = new AquacultureHeadStaffingPolicy();
        AutonomousStaffingPolicy noPrimary = new AutonomousStaffingPolicy() {
            @Override public String capabilityRef() { return base.capabilityRef(); }
            @Override public FormationSpec formationSpec() { return base.formationSpec(); }
            @Override public List<CapabilityGrant> additionalCapabilities() { return base.additionalCapabilities(); }
            @Override public java.util.Optional<WorkerResourceScopeSpec> workerResourceScope() { return base.workerResourceScope(); }
            @Override public PositionContractSpec positionContractSpec() {
                return base.positionContractSpec().withAddress(AquacultureHeadStaffingPolicy.ADDRESS_ALIASES, "");
            }
        };
        assertDegraded(boot(noPrimary, ROUTED), "primaryCapability required");
    }

    @Test
    void primaryCapabilityWithoutRegisteredRouteLeavesTheHeadDegraded() throws Exception {
        assertDegraded(boot(new AquacultureHeadStaffingPolicy(), PositionRouteCatalog.of(List.of())),
                "position-primary-capability-unrouted");
    }

    @Test
    void hoaDeclaresPlanningAsItsPrimaryCapability() {
        assertEquals(AquacultureDomainPlanningCapability.CAPABILITY,
                new AquacultureHeadStaffingPolicy().positionContractSpec().primaryCapability());
    }
}
