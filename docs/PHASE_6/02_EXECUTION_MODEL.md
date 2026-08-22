# METATRON WORKFORCE — PHASE 6 / 02 EXECUTION MODEL

**Authority:** `METATRON WORKFORCE — MASTER EXECUTION PLAN.md`
**Phase:** 6 — Authorization / Execution / Attribution
**Semantic foundation:** Phase 2 canonical entities, lifecycle/state transitions, temporal, provenance, attribution, authorization-reference, and economic-evidence models; Phase 4 organization, delegation, and escalation; Phase 5 working-time, capacity, staffing, resource, and economic constraints; `01_AUTHORIZATION_MODEL.md`.

## 1. Purpose

This artifact defines the Workforce-side execution model that turns legitimately authorized work into attributable institutional execution.

Execution is not equivalent to:

- a proposal existing;
- an assignment existing;
- an authorization being granted;
- a Worker being technically capable;
- sufficient headcount existing;
- a command being received.

Execution admission requires the applicable authorization and reality constraints to be satisfied at the relevant time.

## 2. Execution identity

Every material execution MUST have a stable execution identity.

An execution record MUST be traceable to the applicable:

- work or execution request;
- initiating actor;
- responsible Worker or Workers;
- organization/team context;
- assignment where applicable;
- authorization reference;
- temporal context;
- inputs;
- outputs;
- result;
- evidence/provenance.

Execution identity MUST remain distinct from Worker identity, assignment identity, and authorization identity.

## 3. Execution lifecycle

The execution lifecycle MUST support the canonical semantics:

```text
requested
   ↓
validating
   ↓
authorized
   ↓
running
   ├── completed
   ├── failed
   ├── blocked
   └── cancelled
```

Additional states MAY be introduced only when required by the canonical lifecycle or implementation architecture.

Every material transition MUST preserve:

- current state;
- requested next state;
- initiating actor;
- authority source;
- preconditions;
- temporal validity;
- evidence/provenance;
- resulting event;
- failure behavior.

## 4. Requested execution

A requested execution represents a legitimate request to perform defined work but does not imply that execution is permitted or feasible.

The request MUST preserve enough context to determine:

- what work is requested;
- requested outcome;
- target/resource scope;
- organizational context;
- requested time/window;
- responsible actor or Worker context where known;
- originating request/assignment/proposal;
- relevant priority or deadline where modeled;
- evidence and provenance.

## 5. Validation

Before execution becomes authorized, material execution inputs MUST be validated.

Validation SHOULD establish, where applicable:

- request identity is valid;
- target scope is valid;
- Worker/actor identity is valid;
- assignment remains valid;
- authorization can be resolved;
- authorization is within its temporal window;
- required qualifications remain valid;
- required staffing remains sufficient;
- required capacity remains available;
- required resources remain usable and authorized;
- operating window remains open;
- dependencies remain satisfied;
- cancellation or suspension has not invalidated the request.

Validation failure MUST NOT silently advance execution to `authorized` or `running`.

## 6. Execution admission

Execution may enter `authorized` only when the applicable authorization decision exists and remains valid.

Execution may enter `running` only when execution admission conditions are satisfied at the actual start time.

Conceptually:

```text
Valid Request
AND
Valid Assignment
AND
Valid Authorization
AND
Qualified Worker
AND
Available Capacity
AND
Usable Resources
AND
Open Operating Window
AND
Satisfied Dependencies
=
Execution Admission
```

The exact conjunction depends on the applicable work type and policy. Mandatory constraints MUST NOT be silently ignored.

## 7. Start evidence

Starting execution MUST create attributable evidence showing at least:

- execution identity;
- actor/Worker;
- actual start time;
- applicable organizational context;
- authorization reference;
- assignment reference where applicable;
- relevant inputs;
- start conditions;
- evidence/provenance.

A scheduled start and an actual start MUST remain distinguishable.

## 8. Running execution

While execution is running, material changes to authorization or execution feasibility MUST remain observable.

Examples include:

- authority revoked;
- Worker suspended;
- assignment cancelled;
- resource withdrawn;
- operating window closed;
- capacity exhausted;
- required dependency failed;
- policy changed.

Where the applicable policy requires interruption, the execution MUST NOT continue merely because it was previously admitted.

## 9. Completion

A completed execution MUST preserve evidence sufficient to establish that the applicable completion condition was satisfied.

Completion evidence SHOULD include:

- execution identity;
- actual completion time;
- outputs/results;
- completion condition;
- responsible actor;
- evidence references;
- relevant quantities;
- exceptions or deviations;
- downstream references where applicable.

`completed` MUST NOT mean merely that the Worker stopped running.

## 10. Failure

A failed execution represents an execution attempt that could not satisfy its completion condition.

Failure MUST preserve:

- execution identity;
- actual failure time;
- failure condition;
- responsible actor/Worker context;
- inputs relevant to failure;
- outputs produced before failure;
- evidence/provenance;
- applicable authorization reference;
- retry/recovery information where modeled.

Failure MUST NOT be converted into success by deleting or overwriting the historical failure evidence.

## 11. Blocked execution

A blocked execution represents work that cannot legitimately continue because a blocking condition remains unresolved.

Material blocking conditions MAY include:

- insufficient authority;
- expired/revoked authorization;
- insufficient staffing;
- insufficient capacity;
- unavailable resource;
- unavailable information;
- closed operating window;
- unresolved dependency;
- policy ambiguity;
- organizational conflict;
- economic constraint.

The blocking condition MUST remain explicit and attributable.

Where applicable, the block SHOULD create an escalation candidate under the Phase 4 escalation model.

## 12. Cancellation

Cancellation MUST be distinguishable from failure and completion.

An execution MAY be cancelled only under applicable authority or lifecycle rules.

Cancellation evidence MUST preserve:

- execution identity;
- cancellation actor;
- cancellation authority;
- cancellation time;
- cancellation reason;
- execution state immediately before cancellation;
- evidence/provenance.

A cancelled execution MUST NOT be represented as completed merely because partial work occurred.

## 13. Partial execution

Execution MAY produce partial outputs before failure, blocking, or cancellation.

The implementation MUST preserve the distinction between:

```text
requested quantity
planned quantity
started quantity
completed quantity
remaining quantity
```

where these quantities are material.

Partial completion MUST NOT silently become full completion.

## 14. Runtime binding

Where execution is performed by a runtime Worker instance, the runtime binding MUST remain traceable to the institutional Worker identity.

Conceptually:

```text
Institutional Worker
        ↓
Runtime Worker Instance
        ↓
Execution
```

The runtime instance MUST NOT become an independent institutional identity.

Runtime technical credentials MUST NOT be treated as institutional authority without an explicit authorization relationship.

## 15. Input and output evidence

Execution MUST preserve material inputs and outputs or references to authoritative evidence representing them.

At minimum, the execution record MUST support reconstruction of:

```text
Inputs
  ↓
Action
  ↓
Execution Context
  ↓
Outputs
  ↓
Result
```

Where data is owned by another domain, Workforce SHOULD preserve the external reference rather than manufacture ownership of the external truth.

## 16. Attribution

Execution MUST preserve the attribution chain needed to answer:

- who requested the work;
- who approved or authorized it;
- who was responsible for execution;
- which runtime performed the work;
- what action occurred;
- when it occurred;
- under which authority;
- what result was produced.

A possible chain is:

```text
Human instruction
      ↓
Head decision
      ↓
Authorization
      ↓
Worker assignment
      ↓
Runtime execution
      ↓
Outcome
```

Not every execution contains every actor, but material attribution MUST NOT be collapsed into the final runtime identity.

## 17. Revalidation during execution

Execution MUST support revalidation when material conditions change.

At minimum, revalidation triggers include:

- authorization revocation or expiry;
- delegation change;
- Worker suspension;
- assignment cancellation;
- resource withdrawal;
- capacity exhaustion;
- operating-window closure;
- policy change;
- dependency failure.

The applicable result may be continued execution, blocked, cancelled, or another explicitly defined transition.

The system MUST preserve the reason for the transition.

## 18. Execution versus accounting

Execution may generate operational economic evidence, including:

- labor time;
- resource consumption;
- planned versus actual quantities;
- cost basis;
- utilization evidence;
- execution variance.

Workforce MUST provide evidence to Economy and MUST NOT silently become authoritative accounting truth.

## 19. Historical integrity

Execution history MUST be append-preserving with respect to material institutional truth.

Later correction, retry, re-execution, or recalculation MUST NOT erase the fact that an earlier execution:

- started;
- failed;
- became blocked;
- was cancelled;
- partially completed;
- completed.

A retry MUST be distinguishable from the original execution attempt.

## 20. Failure and retry

A failed or blocked execution MAY create a retry or recovery path where policy permits.

A retry MUST receive its own execution identity or otherwise preserve distinct attempt identity.

Retry authorization MUST be revalidated where material conditions may have changed.

The system MUST NOT treat retry as automatic permission to bypass a prior denial, cancellation, or authority restriction.

## 21. Acceptance invariants

The implementation MUST satisfy at least these invariants:

1. Execution identity is distinct from Worker, assignment, and authorization identity.
2. Requested work is not equivalent to authorized work.
3. Authorized work is not equivalent to executable work.
4. Execution admission respects applicable staffing, capacity, resource, operating-window, dependency, and authorization constraints.
5. Actual start and scheduled start remain distinguishable.
6. Completion requires completion evidence.
7. Failure, blocked, cancellation, partial completion, and completion remain distinguishable.
8. Material execution transitions are attributable and time-valid.
9. Runtime identity does not replace institutional Worker identity.
10. Runtime technical capability does not manufacture institutional authority.
11. Revoked or expired authority cannot silently permit continued execution where policy requires interruption.
12. Historical execution attempts remain reconstructable.
13. Retries remain distinguishable from original attempts.
14. Partial work remains distinguishable from full completion.
15. Workforce preserves economic evidence without becoming authoritative accounting truth.
16. Missing or contradictory execution inputs produce explicit non-execution outcomes rather than optimistic execution.

## 22. Phase 6 contribution

This artifact establishes the execution semantics required to implement execution admission, running work, completion, failure, blocking, cancellation, partial execution, retry, runtime binding, and historical attribution.

It does not prescribe a specific database schema, job runner, queue technology, runtime platform, cloud service, or external execution infrastructure.
