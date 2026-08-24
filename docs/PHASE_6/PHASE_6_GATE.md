# METATRON WORKFORCE — PHASE 6 GATE

**Authority:** `METATRON WORKFORCE — MASTER EXECUTION PLAN.md`
**Phase:** 6 — Authorization / Execution / Attribution
**Status:** ACCEPTANCE RUNNING
**Implementation contract:** `PHASE_6_IMPLEMENTATION_CONTRACT.md`

## 1. Objective

Phase 6 turns proposed work into legitimate institutional execution without manufacturing authority and without losing attribution.

## 2. Acceptance evidence

The executable acceptance evidence is `Phase6AuthorizationExecutionAcceptanceTest` plus Phase 6 unit regression, full regression, and `bootJar` checks in `.github/workflows/phase6.yml`.

The gate remains non-PASS until a fresh CI run proves the frozen contract on the exact current `main` gate commit.

## 3. Required capabilities

- explicit work proposal;
- approval and rejection;
- authorization against an external policy boundary;
- actor / role / authority / scope / action / context / time / resource alignment;
- fail-closed unauthorized execution;
- successful execution evidence;
- explicit execution failure evidence;
- attributable inputs and outputs;
- chronological human → decision → worker → outcome attribution;
- immutable acceptance-surface audit evidence.

## 4. Non-negotiable boundaries

Phase 6 MUST NOT:

- create constitutional legitimacy;
- replace Governance authority;
- replace Gateway authorization/enforcement;
- own execution infrastructure;
- invent resources, approvals, policies or authority;
- treat proposal as approval;
- treat approval as authorization when policy denies the action;
- convert failed execution into success;
- erase or rewrite historical attribution.

## 5. Gate decision

**PENDING CI EVIDENCE.**

A Phase 6 PASS permits progression to **Phase 7 — Reporting / Performance / Economic Evidence** under the Master Execution Plan.
