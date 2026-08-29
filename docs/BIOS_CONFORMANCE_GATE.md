# Workforce BIOS Product Conformance Boundary

## Status

**RUNTIME RECONCILED — PRODUCTION VERIFIED**

This document describes the Workforce-side boundary used when Workforce participates in a BIOS Product / Case / Program flow. BIOS is a Product / Node and does not own Workforce or institutional authority semantics.

## Canonical distinctions

```text
BIOS PRODUCT CONFORMANCE ≠ WORKFORCE GOVERNANCE
BIOS PROGRAM ≠ ASSIGNMENT
USER INTENT ≠ AUTHORIZATION
USER REQUEST ≠ AUTHORIZATION
NATURAL-LANGUAGE EXECUTION INTENT ≠ AUTHORITY PROOF
GUIDANCE ≠ AUTHORIZATION
PUBLICATION ≠ SOT
INTELLIGENCE OUTPUT ≠ EXECUTION EVIDENCE
DECISION ≠ EXECUTION
```

Workforce consumes canonical institutional authority and its own execution semantics. A BIOS request may supply product context, required evidence, consequence, Program information, or a requested action; it cannot manufacture authority.

## Executable runtime boundaries

The reconciled runtime has two distinct BIOS-facing enforcement locations:

```text
com.metatron.workforce.bios.BiosExecutionKernel
```

for the BIOS interaction boundary, and:

```text
com.metatron.workforce.interaction.intelligence.BiosConformanceValidator
com.metatron.workforce.interaction.intelligence.EvidenceBackedGovernance
```

for governed Intelligence results.

`BiosExecutionKernel` no longer treats conversational phrases such as `I authorize`, `approved`, or `thực hiện ngay` as authorization proof. EXECUTION intent fails closed with `BIOS_EXECUTION_ADMISSION_REQUIRED` rather than creating a parallel authority model.

Telegram interaction preserves consequential intent classification while keeping channel identity and natural-language intent outside institutional authority. Requests such as `fix it and deploy` remain execution intent, but the public interaction path returns a deterministic execution-admission block unless a proper institutional execution path is available.

The required invariant is therefore executable:

```text
NATURAL-LANGUAGE EXECUTION INTENT
        ↓
EXECUTION INTENT CLASSIFICATION
        ↓
NO VERIFIED INSTITUTIONAL ADMISSION
        ↓
FAIL CLOSED
```

## Workforce execution authority boundary

`ExecutionAdmissionService` remains the stronger material-execution boundary. It requires Assignment and Authorization and separately verifies required guidance.

```text
GUIDANCE != AUTHORIZATION
PROGRAM != ASSIGNMENT
INTENT != AUTHORITY
```

The interaction and Intelligence layers must not weaken that model.

## Intelligence authority provenance

Web enrichment may propagate authority context supplied by its caller, but Intelligence does not manufacture authority merely because it selected a read-only evidence tool.

Configured provider transports are also no longer represented as fabricated live capacity/quota/latency/cost snapshots. `ConfiguredProviderRoutingPolicy` represents configuration only; `CapacityAwareRoutingPolicy` remains reserved for genuine capacity snapshots.

## Verification evidence

Production source commit:

```text
315c41b89e89bc767b6bf9bec568352faebd6ec3
```

For that exact source revision:

```text
BUILD / TEST: PASS
PRODUCTION DEPLOY: PASS
DEPLOYED SHA IDENTITY: PASS
WORKFORCE LOCAL P95: 0.0031 s
PUBLIC GATEWAY HEALTH: PASS
INTERNET EGRESS: PASS
TELEGRAM WEBHOOK: PASS
WORKFORCE LIVE ACCEPTANCE: PASS
G12 PRODUCTION READINESS: PASS
```

Production deployment run: `33226689228`.
Workforce Live Acceptance run: `33226791833`.
G12 Production Readiness Evidence run: `33226807293`.

Production evidence is kept distinct from repository documentation and from CI-only evidence.

## Remaining boundary rule

This reconciliation does **not** mean natural-language requests can authorize side effects. Any future path that allows a Telegram or Intelligence request to progress from intent into material execution must bind to institutional Assignment / Authorization / execution-admission evidence before side effects occur.
