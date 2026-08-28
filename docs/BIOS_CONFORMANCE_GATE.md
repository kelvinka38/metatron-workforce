# Workforce BIOS Product Conformance Boundary

## Status

**BOUNDARY RECONCILED — RUNTIME PATCH REQUIRED**

This document describes the Workforce-side boundary used when Workforce participates in a BIOS Product / Case / Program flow. BIOS is a Product / Node and does not own Workforce or institutional authority semantics.

## Canonical distinctions

```text
BIOS PRODUCT CONFORMANCE ≠ WORKFORCE GOVERNANCE
BIOS PROGRAM ≠ ASSIGNMENT
USER INTENT ≠ AUTHORIZATION
GUIDANCE ≠ AUTHORIZATION
PUBLICATION ≠ SOT
INTELLIGENCE OUTPUT ≠ EXECUTION EVIDENCE
```

Workforce must consume canonical institutional authority and its own execution semantics. A BIOS request may supply product context, required evidence, consequence, Program information, or a requested action; it cannot manufacture authority.

## Current executable implementation

The current `main` implementation does **not** contain the previously documented `BiosConformanceValidator` / `EvidenceBackedGovernance` runtime gate.

The current BIOS-named executable boundary is:

```text
com.metatron.workforce.bios.BiosExecutionKernel
```

and it is wired through:

```text
com.metatron.workforce.interaction.MetatronInteractionOrchestrator
```

The previous documentation naming `BiosConformanceValidator` as the executable implementation is historical/stale and MUST NOT be used as evidence of current runtime behavior.

## Known semantic defect

`BiosExecutionKernel` currently classifies natural-language execution intent and may treat phrases such as `i authorize`, `approved`, or `thực hiện ngay` as sufficient for its local execution-admission check.

That behavior is not a valid institutional authorization proof.

The required invariant is:

```text
NATURAL-LANGUAGE EXECUTION INTENT
        ↓
INTENT CLASSIFICATION
        ≠
AUTHORIZATION
```

Actual execution must remain fail-closed on the applicable Workforce/institutional authorization mechanism.

## Existing stronger Workforce boundary

`ExecutionAdmissionService` already requires an Assignment and Authorization and separately verifies required guidance. Its semantic distinction is the correct direction:

```text
GUIDANCE != AUTHORIZATION
```

The interaction boundary must not weaken that execution model.

## Required runtime reconciliation

The next implementation patch must:

1. preserve DISCUSSION and REASONING interaction behavior;
2. preserve execution-intent classification;
3. remove conversational phrases as authorization proof;
4. prevent `BiosExecutionKernel` from creating a parallel authority model;
5. route material execution through an actual authorization/admission contract before side effects;
6. preserve provenance requirements for consequential outputs;
7. add tests proving execution intent without authority fails closed;
8. add tests proving an authorized execution path can proceed through the proper authority mechanism;
9. run `./gradlew clean test bootJar --no-daemon` after the patch;
10. keep production deployment/evidence separate from CI evidence.

## Verification status

This document is SOT/downstream reconciliation evidence only. It is **not** CI verification and **not** production evidence.

Runtime status remains:

```text
SOT BOUNDARY: RECONCILED
CURRENT BIOS-NAMED INTERACTION RUNTIME: SEMANTIC PATCH REQUIRED
CI AFTER PATCH: PENDING
PRODUCTION AFTER PATCH: NOT CLAIMED
```
