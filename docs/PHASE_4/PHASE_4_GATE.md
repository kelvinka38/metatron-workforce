# METATRON WORKFORCE — PHASE 4 GATE

**Authority:** `METATRON WORKFORCE — MASTER EXECUTION PLAN.md`
**Phase:** 4 — Organization / Relationships
**Status:** ACCEPTANCE RUNNING
**Execution contract:** `PHASE_4_IMPLEMENTATION_CONTRACT.md`

## 1. Objective

Phase 4 makes Workforce behave as an organization rather than a collection of independent Workers.

## 2. Required capabilities

- organizational hierarchy and context;
- multiple organizational levels;
- position and role occupancy;
- explicit typed reporting relationships;
- bounded delegation;
- authority inheritance/limits without manufacturing authority;
- deterministic escalation routing;
- cross-team coordination;
- temporal validity;
- attributable relationship and escalation history.

## 3. Acceptance evidence

The executable acceptance evidence is `Phase4OrganizationAcceptanceTest` plus Phase 4 unit regression, full regression, and `bootJar` checks in `.github/workflows/phase4.yml`.

The gate remains non-PASS until a fresh CI run proves the frozen contract on the exact current commit.

## 4. Non-negotiable boundaries

Phase 4 MUST NOT:

- create constitutional legitimacy;
- replace Governance authority;
- replace Gateway authorization/enforcement;
- own Execution infrastructure;
- make organizational reachability equivalent to authorization;
- treat delegation as unlimited authority;
- route unresolved escalation to an arbitrary Worker;
- treat cross-team coordination as reporting;
- rewrite historical organizational truth.

## 5. Gate decision

**PENDING CI EVIDENCE.**

A Phase 4 PASS permits progression to **Phase 5 — Time / Capacity / Staffing / Reality** under the Master Execution Plan.
