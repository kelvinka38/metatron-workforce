# WORKFORCE AUTONOMY CLOSURE — IMPLEMENTATION MASTER PLAN

**Status:** CLOSED — P0–P10 TECHNICALLY COMPLETE / `ACCEPTED_L10`  
**Implementation method:** vertical closure with production evidence  
**Target:** L10 institutional autonomy for a material Objective  
**Accepted production SHA:** `c7d19e67797b1f97ba118433bc749bb80defe9d0`  
**Accepted:** 2026-09-01

## 1. Delivery rule

Do not implement random primitives and later infer composition. Each increment SHALL advance the same real Objective path:

```text
submit -> accept -> own -> understand -> plan -> staff -> assign -> authorize
-> schedule -> execute -> observe -> recover/replan -> close -> deliver
```

No phase may bypass canonical domain boundaries for convenience.

## 2. P0 — Contract and migration lock

### Deliver

- map existing `ManagementObjective`, `InstitutionalWork`, Assignment, staffing, execution and evidence structures to canonical contracts;
- identify canonical aggregate/state owner for each field;
- define schema/event versions and migration;
- identify legacy synchronous/in-memory compatibility paths;
- define deprecation/supersession without breaking current production slice;
- define feature flags and rollback/reconciliation strategy.

### Gate

No duplicate Objective, Work, Worker, authority, execution or evidence semantics. Contract tests are versioned before production mutation.

**Final state:** COMPLETE.

## 3. P1 — Durable Objective acceptance and detachment

### Deliver

- `SubmissionEnvelope` and `ObjectiveAcceptance` adapters;
- distinct `RECEIVED`, `ADMITTED`, `ACCEPTED`, `REJECTED`, `PENDING_ACCEPTANCE` states;
- transaction that persists Objective, owner and outbox event before acknowledgement;
- idempotent replay behavior;
- fast status lookup by submission/objective ID;
- channel adapter response that exits without waiting for reasoning/execution.

### Refactor target

`HumanObjectiveIngressService.submit()` must stop owning the full Objective lifetime. `ChannelInteractionIngressService`, Telegram and future ChatGPT/Web adapters submit and render; they do not drive execution.

### Gate

Close interaction immediately after accepted acknowledgement; Objective remains and advances after adapter disconnect and service restart.

**Final state:** COMPLETE / production accepted.

## 4. P2 — Persistent Management Runner

### Deliver

- durable non-terminal Objective scan/event wake-up;
- management lease and fencing token;
- optimistic version/CAS transitions;
- typed timers and delayed work;
- transactional state + outbox commit;
- dead-letter/stuck Objective detection;
- reconciliation after restart.

### Gate

Kill the Runner during a non-terminal Objective. A replacement resumes without duplicate transition, lost ownership or Human action.

**Final state:** COMPLETE / production accepted.

## 5. P3 — Durable messaging and execution transport

### Deliver

- durable outbox/inbox;
- message ID, idempotency key, correlation/causation and schema version;
- at-least-once delivery;
- consumer deduplication;
- durable dispatch/request status;
- dead-letter and reconciliation;
- irreversible-effect idempotency contract.

### Gate

Duplicate, drop, delay and reorder events during tests. Institutional state and effects remain correct and reconstructable.

**Final state:** COMPLETE / accepted invariant coverage.

## 6. P4 — Versioned Work Graph Scheduler

### Deliver

- durable DAG model;
- dependency/conditional edge and join semantics;
- ready-set computation;
- bounded parallelism;
- graph version/supersession;
- stale graph fencing;
- cancellation propagation;
- scheduler decision record.

### Replace

Sequential iteration of execution steps is retained only as a compatibility adapter for trivial bounded work. It is not the general scheduler.

### Gate

At least two independent branches execute concurrently; join unlocks once; replan supersedes old nodes without deleting history.

**Final state:** COMPLETE / production accepted.

## 7. P5 — Workforce allocation

### Deliver

- `WorkDemand` capability/capacity query;
- eligible Worker matching;
- finite capacity reservation/release;
- persistent Assignment lifecycle;
- conflicts/prohibitions checks;
- allocation decision evidence.

### Gate

Unavailable or unqualified Workers cannot receive Work. Capacity is finite, reserved, released and visible to subsequent scheduling.

**Final state:** COMPLETE / production accepted.

## 8. P6 — Autonomous staffing and AI Worker formation

### Deliver

- staffing demand raised automatically from an unsatisfied ready set;
- existing Worker reallocation;
- recognized Participant admission path;
- governed AI Worker formation where a distinct Worker is required;
- qualification and authority gates;
- runtime profile request;
- Worker suspension/release/termination lifecycle;
- typed unresolvable staffing escalation.

### Gate

A real capability/capacity gap is resolved end-to-end without Human manually calling recognition, admission, capability, availability and Assignment APIs. Governance remains fail-closed.

**Final state:** COMPLETE / 45-condition acceptance evidence.

## 9. P7 — Execution/runtime capacity and recovery

### Deliver

- connect scheduler dispatch to canonical Execution;
- integrate compatible async remote runtime transport;
- execution identity and attempts;
- lease/heartbeat/checkpoint/fencing;
- runtime selection/provision/release/replace requests;
- abandoned Work recovery;
- provider/runtime/cloud failure adapters.

### Gate

Kill runtime and provider during execution. Objective and Worker persist; stale attempt cannot commit; replacement attempt completes or returns a typed bounded failure.

**Final state:** COMPLETE / Golden Slice 3 accepted.

## 10. P8 — Observation and evidence closure

### Deliver

- criterion-level evidence requirements;
- Observation request/report adapter;
- independent verification state;
- evidence-quality/variance handling;
- completion package;
- learning-candidate handoff only after verified closure.

### Gate

A successful execution with insufficient evidence remains non-completed. Objective completes only after every required criterion receives a valid Observation result.

**Final state:** COMPLETE / production accepted.

## 11. P9 — Workplace control and communication surface

### Deliver

- reuse canonical Conversation, Meeting, Meeting Room and Decision objects;
- durable Objective/Work/Decision/evidence references;
- canonical progress projection;
- Human exception/approval interactions;
- cross-channel query/notification/delivery;
- channel-independent conversation continuity.

### Scope

This phase authorizes the Workplace integration required by Autonomy Closure. It does not authorize redesigning or replacing the frozen full Workplace architecture.

### Gate

Submit through one channel, query through another and deliver through an authorized third surface without replacing Objective, Worker, Conversation or evidence identity.

**Final state:** COMPLETE for Autonomy Closure scope / invariant evidence accepted.

## 12. P10 — Golden slices and formal acceptance

Execute in order:

1. read-only four-repository institutional audit;
2. governed repository mutation to PR without merge;
3. adversarial failure/recovery matrix;
4. bounded elastic fan-out/join capacity.

Every run binds exact source/deploy identity, Objective/owner/graph/Assignment/authorization/execution/Observation references, failure injection, cost and closure package.

**Final state:** COMPLETE.

Accepted production evidence:

- GS1/GS2 run `33465333035` — SUCCESS
- GS3/GS4 run `33465332814` — SUCCESS
- final ratification run `33465915027` — SUCCESS
- Golden Slices `4/4 PASS`
- mandatory production conditions `45/45 PASS`
- unresolved critical contradictions `0`
- final verdict `ACCEPTED_L10`

## 13. Operational safeguards

Before mutating autonomy is enabled:

- Objective/organization/capability/provider kill switches;
- budget and attempt ceilings;
- authority expiry/revocation propagation;
- irreversible-action idempotency;
- human approval points;
- secret and tenant isolation;
- audit and incident reconstruction;
- rollback/reconciliation runbook.

These safeguards remain mandatory after acceptance; acceptance is not permission to weaken them.

## 14. Definition of done

The program is done only when upstream `WORKFORCE_AUTONOMY_CLOSURE_ACCEPTANCE_SPEC.md` returns `ACCEPTED_L10` for a material production Objective. Earlier phases and bounded slices must report their exact achieved level.

**Satisfied on 2026-09-01.** Final independent ratification returned `ACCEPTED_L10` against exact source/deployed SHA `c7d19e67797b1f97ba118433bc749bb80defe9d0` with 4/4 Golden Slices, 45/45 mandatory production conditions, and zero unresolved critical contradictions.

Final evidence artifact: `9784967732`  
SHA-256: `1aa44620296eb9bc159ecca81b9c2473370b1b69712d8ef1d601c0c39c69e369`

## 15. Post-closure state

```text
P0–P10 = COMPLETE
WORKFORCE AUTONOMY CLOSURE = CLOSED / ACCEPTED_L10
```

This program defines no P11. Subsequent work is post-closure Workforce product/operations evolution governed by the Workforce SOT and any newly approved scope. The existence of later work must not be misread as evidence that P0–P10 remained incomplete.
