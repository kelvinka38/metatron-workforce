package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.core.FileWorkforceCoreStateStore;
import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.management.AquacultureDomainPlanningRoute;
import com.metatron.workforce.management.AquacultureHeadStaffingPolicy;
import com.metatron.workforce.management.AutonomousStaffingPolicy;
import com.metatron.workforce.operating.PositionAddressResolver;
import com.metatron.workforce.operating.WorkerConstitutionService;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Real Workforce Core + Worker Constitution state with appointed Positions, and the production planner
 * composition wired to a PositionAddressResolver over that state. No LLM on the addressing path.
 */
final class PositionAddressFixture {
    static final Instant AT = Instant.parse("2026-09-26T00:00:00Z");

    final WorkforceCoreService core;
    final WorkerConstitutionService constitution = WorkerConstitutionService.inMemory();
    final PositionAddressResolver resolver;
    final List<PositionWorkRoute> routes = new ArrayList<>(List.of(new AquacultureDomainPlanningRoute()));

    PositionAddressFixture(Path dir) {
        core = new WorkforceCoreService(new FileWorkforceCoreStateStore(dir.resolve("core-" + System.nanoTime() + ".json")));
        resolver = new PositionAddressResolver(core, constitution);
    }

    PositionAddressFixture appointHeadOfAquaculture() {
        return appoint(new AquacultureHeadStaffingPolicy());
    }

    /** Admits the policy's Worker into its Position and materializes its standing constitution, as staffing does. */
    PositionAddressFixture appoint(AutonomousStaffingPolicy policy) {
        AutonomousStaffingPolicy.FormationSpec spec = policy.formationSpec();
        core.recognizeParticipant(spec.participantId(), spec.participantType(), spec.participantProvenanceRef());
        core.admitWorker(spec.workerId(), spec.participantId());
        core.participate(spec.participationId(), spec.workerId(), spec.organizationRef(), spec.positionRef(), spec.roleRef());
        constitution.ensureConstitution(policy, AT);
        return this;
    }

    ExecutionPlanProposalService planner(ExecutionPlanProposalService frontier) {
        return new GeneralActionComposingExecutionPlanProposalService(
                new FounderWorkerExecutionPlanProposalService(frontier, resolver, routes));
    }

    /** A Position that exists only in this test: declares its own alias and primary capability, nothing else. */
    static AutonomousStaffingPolicy position(String workerId, String positionRef, String roleRef,
                                             String capabilityRef, List<String> aliases) {
        return new AutonomousStaffingPolicy() {
            @Override public String capabilityRef() { return capabilityRef; }

            @Override
            public FormationSpec formationSpec() {
                String slug = workerId.toLowerCase();
                return new FormationSpec(true, "participant:" + slug, WorkforceCoreService.ParticipantType.AI,
                        "provenance:" + slug, workerId, "bios", "participation:" + slug, positionRef, roleRef,
                        1.0, "evidence:" + slug, "qualification:" + slug, "evidence:qualification:" + slug,
                        "policy:" + slug, 1.0, "profile:" + slug, "cost:" + slug, "lifecycle:" + slug);
            }

            @Override
            public PositionContractSpec positionContractSpec() {
                return AutonomousStaffingPolicy.super.positionContractSpec().withAddressAliases(aliases);
            }
        };
    }
}
