# PHASE 9 / D3 — G9 DEPENDENCY COVERAGE

## Objective

Close the remaining Phase 9 completeness requirement: the Workforce domain must
represent the complete external dependency surface named by Phase 9 without
absorbing ownership or authority from those domains.

D3 does not replace D1 or D2.

- D1 defines the six canonical executable integration contracts.
- D2 turns those six contracts into executable boundary semantics.
- D3 establishes complete G9 coverage for all Phase 9 external dependencies.

## Phase 9 dependency surface

1. Governance
2. Authorization
3. Gateway
4. Execution
5. Observation
6. Knowledge
7. Economy
8. Data

Governance and Data are included at the G9 dependency layer because the Master
Execution Plan explicitly names them as Phase 9 integration areas, while the
D1 executable contract set remains the six canonical boundaries already
accepted.

## Required dependency dimensions

Every dependency must declare:

1. owner domain;
2. interface;
3. input contract;
4. output contract;
5. authority boundary;
6. failure behavior;
7. evidence/provenance requirement.

## Ownership rule

The dependency registry is descriptive and traceability-oriented. It does not
create external authority, external records, gateway enforcement, accounting
truth, observation truth, or institutional knowledge inside Workforce.

## Acceptance gate

D9-D3 passes only when:

- all eight Phase 9 dependency areas are represented;
- every dependency has all seven required dimensions;
- every D2 executable boundary is traceable to a G9 dependency;
- Governance and Data are covered without granting Workforce their authority;
- provenance is mandatory and explicitly preservable;
- Workforce is never the owner of an external dependency;
- the full Phase 8 + Phase 9 regression suite passes.

## Regression gate

The full regression suite is executed by `.github/workflows/phase9-d3.yml`.

## Gate

**D9-D3 = PASS only after full regression acceptance.**

After D9-D3, Phase 9 may be closed only when the complete G9 gate is accepted.
Phase 10 remains blocked until that closure is explicitly established.
