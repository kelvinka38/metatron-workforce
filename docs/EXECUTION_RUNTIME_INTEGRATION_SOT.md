# EXECUTION_RUNTIME_INTEGRATION_SOT.md

## Status

CANONICAL

## Purpose

Define the cross-domain contract between Workforce, Execution, Worker Runtime implementation, and Technical Infrastructure.

This document is an integration contract. It is not a Runtime institutional domain SOT.

## Non-Goals

This document does not create:

- Runtime domain
- Runtime authority model
- Runtime governance model
- Runtime institutional identity

## Canonical Ownership

Universal / Governance owns institutional rules and authority boundaries.

Gateway owns external boundary enforcement.

Workforce owns worker-side institutional semantics:

- Worker identity
- Role
- Capability
- Assignment
- Authorization context

Execution owns execution semantics:

- Execution request
- Execution lifecycle
- Execution result
- Execution evidence

Worker Runtime is an implementation construct that realizes execution.

Technical Infrastructure provides production substrate:

- Compute
- Storage
- Messaging
- Network
- Observability

## Canonical Chain

Human / External Actor
→ Gateway
→ Workforce
→ Worker
→ Assignment
→ Authorization
→ Execution Request
→ Worker Runtime Instance
→ Execution
→ Outcome
→ Evidence / Observation

## Identity Semantics

worker_id:
Persistent institutional Worker identity.

assignment_id:
Institutional assignment reference.

authorization_id:
Authority context reference.

execution_id:
Institutional execution identity.

runtime_id:
Technical runtime-instance identity.

## Invariants

1. Worker ≠ Runtime.
2. Runtime ≠ Execution.
3. Execution ≠ Infrastructure.
4. Runtime termination does not imply Worker retirement.
5. runtime_id must not replace worker_id.
6. Infrastructure choices must not alter institutional semantics.

## Runtime Failure Semantics

A runtime failure may require:

- recovery
- rebinding
- continuation
- replay handling
- failure reporting

The mechanism is implementation-specific and must be proven by evidence.

## Cloud Independence

Execution semantics must remain independent from:

- cloud provider
- container platform
- VM technology
- orchestration technology
- messaging technology

## Acceptance Criteria

The contract is satisfied when:

- Assignment to Execution handoff is defined.
- Worker identity survives runtime lifecycle changes.
- Execution identity remains attributable.
- Runtime implementation can be replaced without changing institutional semantics.
