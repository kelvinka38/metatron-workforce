# PHASE 11 — HARDENING / ACCEPTANCE

## Gate

G11 — Workforce Hardening / Acceptance

## Objective

Move from "it works" to "it behaves correctly under institutional constraints."

## Acceptance Coverage

| Domain | Acceptance Evidence | Status |
|---|---|---|
| Authorization | `Phase11HardeningAcceptanceTest.authorizationAllowedDeniedExpiredAndDelegatedPathsRemainExplicit` | PENDING EXECUTION |
| Organization / bounded delegation | Delegation scope, organization context and expiry assertions | PENDING EXECUTION |
| Capacity | Phase 10 capacity-deficit and scale assertions | PENDING EXECUTION |
| Economic integrity | Planned vs actual contribution and authority-boundary assertions | PENDING EXECUTION |
| Communication | Existing Phase 3 communication acceptance suite | PENDING FULL SUITE |
| Execution | Authorization binding, blocked, failure and terminal-state assertions | PENDING EXECUTION |
| Learning | Failed execution cannot become validated learning | PENDING EXECUTION |
| Recovery | Failure preservation and terminal-state protection | PENDING EXECUTION |

## Critical Failure Conditions

G11 FAIL if any critical failure remains in:

- authorization
- attribution
- economic integrity
- state consistency / corruption
- data loss
- institutional boundary enforcement

## Implementation

Phase 11 hardening gate aggregation is implemented by:

`src/main/java/com/metatron/workforce/phase11/Phase11HardeningService.java`

Acceptance tests are implemented by:

`src/test/java/com/metatron/workforce/phase11/Phase11HardeningAcceptanceTest.java`

## Current Decision

PENDING EXECUTION

A PASS may only be recorded after the full repository test suite completes successfully and no critical failure is observed.

## Evidence

CI / local test execution output must be attached to the G11 evidence record before the gate is closed.
