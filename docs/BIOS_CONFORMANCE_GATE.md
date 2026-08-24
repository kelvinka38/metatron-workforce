# Workforce BIOS Conformance Gate

## Status

**IMPLEMENTED — CI VERIFICATION REQUIRED**

This document defines the Workforce-side machine-checkable BIOS boundary. It does not redefine the canonical BIOS or Universal SOT.

## Enforced invariants

1. Governed reasoning requires non-empty evidence references.
2. Evidence placeholders (`unknown`, `none`, `n/a`, `no evidence`) are rejected.
3. Evidence references must be unique.
4. Every intelligence response must be non-empty and provider-attributed.
5. Provider attribution must be unique within a governed result.
6. Decision-or-higher modes require authority context.
7. Execution requires authority context.
8. Consequence must be explicit.
9. Casual/discussion modes cannot carry governed consequence levels.
10. Critical and irreversible consequences require:
    - multi-provider collaboration mode;
    - provider budget of at least two;
    - at least two distinct successful provider responses;
    - explicit authority context.
11. Intelligence output cannot be treated as execution evidence or manufactured success.

## Boundary

This gate is intentionally deterministic. It validates only semantics represented in the Workforce intelligence envelope. It does not invent or replace upstream BIOS semantics such as institutional reasoning depth selection, Universal traceability, authority ownership, falsification methodology, or external reality validation.

## Verification

The executable implementation is `BiosConformanceValidator`, wired through `EvidenceBackedGovernance` and covered by `BiosConformanceValidatorTest`.

CI must execute:

```text
./gradlew clean test bootJar --no-daemon
```

Production deployment remains a separate operational gate and must not be declared complete from source/CI evidence alone.
