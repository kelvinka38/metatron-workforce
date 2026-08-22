package com.metatron.workforce.phase9;

import java.util.List;

public final class Phase9IntegrationRegistry {

    private Phase9IntegrationRegistry() {
    }

    public static List<IntegrationContract> canonicalContracts() {
        return List.of(

            new IntegrationContract(
                "INT-WORKFORCE-AUTHORIZATION",
                "WORKFORCE",
                "AUTHORIZATION",
                "Authorize proposed institutional work before execution",
                "actor, role, scope, action, context, time, resource",
                "authorization decision + authority reference",
                "authorization authority remains external to Workforce",
                "deny execution when authorization is unavailable, expired, revoked, or out of scope",
                "preserve actor, authority reference, decision time, and decision provenance"
            ),

            new IntegrationContract(
                "INT-WORKFORCE-GATEWAY",
                "WORKFORCE",
                "GATEWAY",
                "Cross the external interaction boundary only through authorized gateway semantics",
                "authorized external interaction request",
                "external interaction result + gateway reference",
                "gateway remains the authoritative external interaction boundary",
                "fail closed when gateway rejects, is unavailable, or boundary conditions are violated",
                "preserve request identity, authorization context, gateway decision, and result"
            ),

            new IntegrationContract(
                "INT-WORKFORCE-EXECUTION",
                "WORKFORCE",
                "EXECUTION",
                "Coordinate legitimate work execution without owning execution infrastructure",
                "authorized execution request",
                "execution reference + execution outcome/evidence",
                "Workforce coordinates execution but does not become execution infrastructure",
                "execution failure remains attributable and does not become success",
                "preserve actor, authorization, execution identity, timestamps, inputs, outputs, and result"
            ),

            new IntegrationContract(
                "INT-WORKFORCE-OBSERVATION",
                "WORKFORCE",
                "OBSERVATION",
                "Consume observations/evidence without manufacturing observed truth",
                "observation/evidence reference",
                "observation identity + provenance + measurement",
                "Workforce does not manufacture observed truth",
                "missing or invalid observation remains unavailable rather than fabricated",
                "preserve source, observation identity, timestamp, provenance, and confidence"
            ),

            new IntegrationContract(
                "INT-WORKFORCE-KNOWLEDGE",
                "WORKFORCE",
                "KNOWLEDGE",
                "Submit validated learning candidates without silently creating institutional knowledge",
                "validated learning/evidence package",
                "knowledge admission decision + knowledge reference",
                "institutional knowledge admission remains external to Workforce",
                "unvalidated learning remains a Workforce learning artifact",
                "preserve originating worker, evidence lineage, validation evidence, and admission decision"
            ),

            new IntegrationContract(
                "INT-WORKFORCE-ECONOMY",
                "WORKFORCE",
                "ECONOMY",
                "Emit economic evidence required for authoritative economic processing",
                "worker, organization, work, time, resource, cost/allocation evidence",
                "economic consequence/reference",
                "authoritative economic processing and accounting truth remain external to Workforce",
                "Workforce may provide operational economic evidence but cannot authorize, alter, or manufacture accounting truth",
                "preserve work identity, worker identity, time, resource, cost basis, allocation basis, and outcome"
            )
        );
    }
}
