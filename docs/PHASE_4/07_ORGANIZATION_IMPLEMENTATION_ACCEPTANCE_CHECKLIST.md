# METATRON WORKFORCE — PHASE 4 ACCEPTANCE CHECKLIST

**Authority:** `METATRON WORKFORCE — MASTER EXECUTION PLAN.md`
**Phase:** 4 — Organization / Relationships
**Contract:** `PHASE_4_IMPLEMENTATION_CONTRACT.md`
**Status:** ACCEPTANCE RUNNING

## Organization structure

- [ ] Institution → organization → department → team hierarchy is representable.
- [ ] Position is distinct from Worker.
- [ ] Role is distinct from Position and Authority.
- [ ] Worker occupancy is temporal and attributable.
- [ ] Multiple organizational levels are demonstrated.

## Relationships

- [ ] REPORTS_TO works.
- [ ] MANAGES works.
- [ ] SUPERVISES works.
- [ ] ADVISES works.
- [ ] COORDINATES_WITH works without creating reporting authority.
- [ ] DELEGATES_TO remains distinct from ordinary reporting.
- [ ] Relationship validity is time-aware.
- [ ] Historical attribution/evidence is preserved.

## Delegation and authority limits

- [ ] Delegation is explicit.
- [ ] Delegation is bounded by authority reference.
- [ ] Delegation is scope-limited.
- [ ] Delegation is context-limited.
- [ ] Delegation is time-limited/expiry-aware.
- [ ] Delegation cannot exceed delegator scope.
- [ ] Expired delegation is not effective.
- [ ] Authorization is evaluated independently.

## Escalation

- [ ] Real Worker/work context can raise escalation.
- [ ] All eight Gate G4 categories are represented.
- [ ] Urgency is explicit.
- [ ] Explicit route has precedence.
- [ ] Reporting/management/supervision route is deterministic.
- [ ] Valid delegation can provide a route.
- [ ] Cross-team coordination is not treated as reporting.
- [ ] Missing/ambiguous route becomes explicit unresolved state.
- [ ] Escalation does not authorize execution.
- [ ] Escalation lifecycle preserves timestamps and attribution.

## Authorization / ownership boundaries

- [ ] Restricted mutations evaluate injected authorization policy.
- [ ] Organization does not manufacture constitutional legitimacy.
- [ ] Organization does not replace Gateway enforcement.
- [ ] Escalation routing does not create authority.
- [ ] External authoritative domains remain owners of their authority/truth.

## Evidence

Every checked item must be supported by the fresh Phase 4 acceptance test, unit regression, and CI run on the exact gate commit.
