# Elastic Worker Actor Runtime Gap Closure

Status: IMPLEMENTATION + ACCEPTANCE CONTRACT — 2026-09-11

> **Downstream concurrency program — Founder approved 2026-09-12:** Worker actor elasticity does not by itself prove safe concurrent execution. Any work involving execution concurrency, repository mutation, mutable workspaces, build isolation, resource capacity/leases, integration/merge, or deployment isolation MUST also read and follow:
> - `docs/ARCHITECTURE/ELASTIC_EXECUTION_WORKSPACE_RESOURCE_ISOLATION_GAP_CLOSURE.md`
> - `docs/ARCHITECTURE/ELASTIC_EXECUTION_WORKSPACE_RESOURCE_ISOLATION_DETAILED_SPEC.md`
> - `docs/ARCHITECTURE/ELASTIC_EXECUTION_WORKSPACE_RESOURCE_ISOLATION_EXECUTION_PLAN.md`
>
> The downstream program extends the existing `ExecutionAttempt` / `AutonomySchedulingService` / ActionFabric / Highway authorities; it does not replace them.

## Problem
Metatron already has durable Worker identity, participation, constitution, capability, memory, Assignment, ExecutionAttempt and logical RuntimeInstance state. However, Worker cognition is multiplexed through one shared IntelligenceFabric and autonomous execution is coordinated by one central management loop. A large Worker registry therefore does not yet prove that each Worker is an independently operating institutional actor.

The target is not one model, process, thread or container per Worker. Compute is shared and elastic. The institutional unit of independence is the Worker actor: canonical identity + durable mailbox + durable actor state + scoped cognition/work session + assignment ownership + recoverable progress.

## Invariants

```text
WORKER != MODEL
WORKER != THREAD
WORKER != CONTAINER
ACTOR IDENTITY != COMPUTE ALLOCATION
WORKER COUNT != CONCURRENCY
ROLE != AUTHORITY
CAPABILITY != AUTHORITY
SHARED INTELLIGENCE != SHARED WORKER STATE
```

There is no hard-coded Worker-count ceiling. Registered Worker count is bounded by durable storage/governance; active execution concurrency is bounded independently by configured compute/provider/tool capacity.

## Target architecture

```text
Canonical Worker Registry
        |
        v
Elastic Worker Actor Runtime
  actor:<worker-id>
  - durable state machine
  - durable mailbox
  - heartbeat
  - current objective / assignment / step
  - last action / next action
  - per-Worker serial work lane
        |
        +-----------------------+
        |                       |
        v                       v
Shared IntelligenceFabric   Shared capability/tool pools
(OpenAI/Gemini/Claude)      (repo, shell, research, etc.)
```

Shared providers supply computation. They do not own Worker identity, memory, Assignment or actor state.

## Gap closure

### A1 — Canonical Worker Actor
Every ACTIVE canonical Worker has one stable `actor:<worker-id>` actor identity with durable state and heartbeat.

Required states:
`IDLE`, `READY`, `WORKING`, `WAITING`, `BLOCKED`, `PAUSED`, `RECOVERING`, `OFFLINE`.

### A2 — Durable per-Worker mailbox
Every Worker has an isolated durable mailbox. Human messages, Assignment notifications, delegation messages and system/recovery events are attributed to one target Worker and retain sender/objective/assignment/step provenance.

### A3 — Per-Worker work lane
Worker cognition executes through an actor-scoped serial lane. Different Workers may execute concurrently according to configured compute capacity. The same Worker cannot accidentally overlap two serial actor turns unless a future Position policy explicitly permits it.

### A4 — Durable recovery
Actor state/mailbox survives JVM/container replacement. In-flight actor messages that cannot be safely resumed are marked reconciliation-required; canonical Assignment/ExecutionAttempt state remains authoritative for recovery.

### A5 — Shared brain, isolated cognition session
Every Worker cognition request is scoped with canonical `worker_id` and actor state. Worker memory, constitution, Assignment and evidence remain Worker-specific while the underlying IntelligenceFabric/provider pool is shared.

### A6 — Organizational work/delegation substrate
Mailbox envelopes support Worker→Worker delegation without creating a second execution stack. Delegation is an attributed request; actual Assignment/capability/authority still goes through Workforce governance.

### A7 — Control Room observability
The Workplace API exposes actor identity/state/heartbeat/mailbox depth/current objective/current Assignment/current step/last action/next action. Founder controls may pause/resume an actor independently of Worker identity.

## Elasticity model

```text
registered_workers = N                 # no hard-coded architectural limit
active_actors <= N
working_actors <= active_actors
concurrent_actor_turns <= configured compute capacity
provider/tool concurrency <= their own policy/capacity
```

Idle actors retain durable identity/state without consuming a dedicated OS thread/process/container. Actor turns run on shared virtual-thread compute and a configurable global concurrency permit.

## Acceptance
1. Create multiple canonical Workers; each materializes a distinct actor ID and mailbox.
2. Run two Worker cognition requests concurrently; they may overlap in time, but each actor keeps isolated state/mailbox/requester identity.
3. Submit two turns to the same Worker; actor runtime serializes them.
4. One Worker may be `BLOCKED` or `PAUSED` while another remains `WORKING`.
5. Persist state/mailboxes, reconstruct runtime, and prove actor identity/mailbox continuity.
6. Reconcile canonical Assignments into actor state/mailbox without inventing completion.
7. Scale acceptance creates a configurable N Workers (test N is only a test parameter, never an architecture limit) and proves no fixed Worker-count ceiling in runtime code.
8. Existing governed Assignment/Authorization/ExecutionAttempt/Observation contracts remain intact.

## Definition of done
Implementation is complete only when actor storage/runtime/cognition wiring/Assignment reconciliation/Control Room observability are built, focused acceptance and full build pass, and production is deployed/verified on one exact immutable SHA.
