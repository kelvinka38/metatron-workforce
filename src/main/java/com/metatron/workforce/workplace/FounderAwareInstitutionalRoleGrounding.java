package com.metatron.workforce.workplace;

import com.metatron.workforce.operating.WorkerConstitutionService;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Canonical grounding bridge for Founder-defined runtime Positions.
 *
 * <p>Institutional domain SOT remains preferred. A Founder-defined Worker whose role has no pre-existing
 * institution-domain binding is grounded by its durable Position operating contract instead of being
 * blocked or allowing the provider to invent a persona.</p>
 */
@Service
@Primary
public final class FounderAwareInstitutionalRoleGrounding implements InstitutionalRoleGrounding {
    private final GitHubCanonicalInstitutionalRoleGrounding canonical;
    private final WorkerConstitutionService constitution;

    public FounderAwareInstitutionalRoleGrounding(
            GitHubCanonicalInstitutionalRoleGrounding canonical,
            WorkerConstitutionService constitution) {
        this.canonical = Objects.requireNonNull(canonical, "canonical");
        this.constitution = Objects.requireNonNull(constitution, "constitution");
    }

    @Override
    public Grounding resolve(String roleRef, String positionRef, String requestedRole, String userMessage) {
        Grounding upstream = canonical.resolve(roleRef, positionRef, requestedRole, userMessage);
        if (upstream.available()) return upstream;

        boolean founderDefined = (roleRef != null && roleRef.startsWith("role:founder-defined:"))
                || (positionRef != null && positionRef.startsWith("position:founder-defined:"));
        if (!founderDefined || positionRef == null || positionRef.isBlank()) return upstream;

        return constitution.contractForPosition(positionRef)
                .map(contract -> {
                    String context = """
                            CANONICAL FOUNDER-DEFINED POSITION CONTRACT
                            contract_id=%s
                            organization_ref=%s
                            position_ref=%s
                            role_ref=%s
                            mission=%s
                            responsibilities=%s
                            reporting_lines=%s
                            capability_requirements=%s
                            authority_scopes=%s
                            resource_scopes=%s
                            escalation_routes=%s
                            success_measures=%s
                            decision_rights=%s
                            operating_coverage=%s
                            working_time_zone=%s
                            max_concurrent_assignments=%s
                            """.formatted(
                            contract.contractId(), contract.organizationRef(), contract.positionRef(),
                            contract.roleRef(), contract.mission(), contract.responsibilities(),
                            contract.reportingLines(), contract.capabilityRequirements(), contract.authorityScopes(),
                            contract.resourceScopes(), contract.escalationRoutes(), contract.successMeasures(),
                            contract.decisionRights(), contract.operatingCoverage(), contract.workingTimeZone(),
                            contract.maxConcurrentAssignments());
                    List<String> evidence = new ArrayList<>(contract.evidenceReferences());
                    evidence.add("position-contract:" + contract.contractId());
                    evidence.add("institutional-grounding:founder-defined-position");
                    return Grounding.available("FOUNDER_DEFINED_POSITION", context, evidence.stream().distinct().toList());
                })
                .orElse(upstream);
    }
}
