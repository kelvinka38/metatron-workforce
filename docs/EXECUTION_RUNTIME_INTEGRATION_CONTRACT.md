# EXECUTION RUNTIME INTEGRATION CONTRACT

## Status
DERIVED IMPLEMENTATION CONTRACT — NON-AUTHORITATIVE

## Canonical Upstream
Institutional authority resides in `kelvinka38/metatron-institution`, including Workforce and Execution SOTs and approved derived specifications. This file is implementation-facing only.

## Boundary
- Gateway owns external boundary enforcement.
- Workforce owns Worker-side institutional semantics: persistent Worker identity, participation, Work relationships, capability, assignment and operating reality while consuming external authority/authorization references.
- Execution owns execution request, lifecycle, result and execution evidence semantics.
- Worker Runtime is a replaceable implementation construct.
- Technical Infrastructure provides compute, storage, messaging, network and observability substrate.

## Integration Chain
Participant → Gateway → Workforce → Worker → Work / Assignment → Authorization Reference → Execution Request → Runtime → Execution → Outcome → Evidence / Observation

## Identity Semantics
`worker_id` is persistent Worker identity; `work_id` Work identity; `assignment_id` assignment reference; `authorization_id` authorization context; `execution_id` execution identity; `runtime_id` technical runtime-instance identity.

## Invariants
1. Worker ≠ Runtime.
2. Work ≠ Assignment ≠ Authorization ≠ Execution.
3. Runtime ≠ Execution.
4. Execution ≠ Infrastructure.
5. Runtime termination does not imply Worker retirement or Work loss.
6. `runtime_id` never replaces `worker_id` or `execution_id`.
7. Infrastructure/provider changes do not alter institutional semantics.
8. This contract defers to canonical institutional SOT on conflict.

## Failure / Conformance
Runtime failure may require recovery, rebinding and continuation, but may not manufacture successful Outcome without evidence. Conformance requires explicit Assignment→Execution handoff, persistent Worker/Work identity across runtime replacement, attributable Execution and evidence-backed recovery.
