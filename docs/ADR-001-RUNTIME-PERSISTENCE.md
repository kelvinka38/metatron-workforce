# ADR-001 — Runtime Persistence

## Status

ACCEPTED FOR CURRENT DEPLOYABLE RUNTIME

## Context

The Execution Runtime Integration contract requires runtime identity to survive runtime lifecycle changes and requires failure/recovery mechanisms to be proven by evidence.

The previous implementation used an in-memory `ConcurrentHashMap`, which could not survive JVM replacement.

## Decision

Introduce a `RuntimePersistenceStore` abstraction and use an atomic filesystem-backed implementation (`FileRuntimePersistenceStore`) for the current single-node deployable runtime.

The default no-argument `WorkforceRuntime` remains process-local for unit-test isolation. The deployable runtime explicitly supplies a persistence root.

Persistence stores only technical runtime continuity metadata:

- `runtime_id`
- `worker_id`
- runtime `state`
- persistence timestamp

It does not become the owner of Workforce identity, authorization, or Execution semantics.

## Atomicity

Each record is written to a temporary file and moved into place with `ATOMIC_MOVE` where supported, with a replace-existing fallback where the filesystem does not expose atomic moves.

## Recovery Contract

A replacement `WorkforceRuntime` constructed against the same persistence root must recover the same `runtime_id`, `worker_id`, and persisted runtime state.

A failed runtime remains `FAILED` after recovery. Recovery must never manufacture execution success.

## Boundary

This decision proves durable continuity for the current deployable single-node runtime. It does not claim multi-node distributed persistence, HA, or a production database. Those require workload/capacity evidence before selecting a larger substrate.

## Evidence

- `RuntimeDurableRecoveryTest`
- G12 production runtime evidence
- `.github/workflows/g12-production-readiness.yml`
