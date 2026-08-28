# EXECUTION RUNTIME INTEGRATION CONTRACT

## Status

DERIVED IMPLEMENTATION CONTRACT — NON-AUTHORITATIVE

## Canonical Upstream

Institutional authority resides in `kelvinka38/metatron-institution`, including the Workforce and Execution capability SOTs and their approved derived specifications.

This file is an implementation-facing conformance contract only. It does not redefine canonical ontology, institutional authority, or domain ownership.

## Purpose

Define the implementation boundary between Workforce, Execution, Worker Runtime implementation, and Technical Infrastructure.

## Non-Goals

This contract does not create a Runtime institutional domain, authority model, governance model, Worker identity model, or competing Source of Truth.

## Ownership Boundary

Universal / Governance owns institutional rules and authority boundaries.

Gateway owns external boundary enforcement.

Workforce owns worker-side institutional semantics, including persistent Worker identity, participation, work relationships, capability and assignment semantics, while consuming external authority/authorization references.

Execution owns execution request, lifecycle, result and execution evidence semantics.

Worker Runtime is an implementation construct that realizes execution and remains replaceable.

Technical Infrastructure provides production substrate such as compute, storage, messaging, network and observability.

## Integration Chain

External Actor / Participant
→ Gateway
→ Workforce
→ Worker
→ Work / Assignment
→ Authorization Reference
→ Execution Request
→ Worker Runtime Instance
→ Execution
→ Outcome
→ Evidence / Observation

## Identity Semantics

- `worker_id`: persistent institutional Worker identity.
- `work_id`: institutional Work identity.
- `assignment_id`: institutional assignment reference.
- `authorization_id`: authorization context/reference.
- `execution_id`: execution identity.
- `runtime_id`: technical runtime-instance identity.

## Invariants

1. Worker ≠ Runtime.
2. Work ≠ Assignment ≠ Authorization ≠ Execution.
3. Runtime ≠ Execution.
4. Execution ≠ Infrastructure.
5. Runtime termination does not imply Worker retirement or Work loss.
6. `runtime_id` must not replace `worker_id` or `execution_id`.
7. Infrastructure/provider changes must not alter institutional semantics.
8. This implementation contract must defer to canonical institutional SOT on conflict.

## Runtime Failure Semantics

Runtime failure may require recovery, rebinding, continuation, replay handling and failure reporting. No recovery mechanism may manufacture a successful institutional outcome without evidence.

## Provider Independence

Execution semantics must remain independent from cloud provider, container platform, VM technology, orchestration technology, messaging technology, or AI model/provider.

## Conformance Criteria

The implementation conforms when Assignment-to-Execution handoff is explicit, Worker and Work identities survive runtime lifecycle changes, Execution remains attributable, Runtime can be replaced without changing institutional semantics, and evidence demonstrates those properties.
