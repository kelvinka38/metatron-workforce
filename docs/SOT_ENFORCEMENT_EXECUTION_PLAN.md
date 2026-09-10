# METATRON SOT ENFORCEMENT — EXECUTION PLAN

**Status:** DERIVED DRAFT — READY FOR FOUNDER/IMPLEMENTATION RATIFICATION  
**Date:** 2026-09-10  
**Baseline:** `kelvinka38/metatron-workforce@12a5421b4f3c86e92fb3975a759c804f5adfacf9`  
**Upstream:** Founder-approved SoT Enforcement Detailed Gap Closure + `SOT_ENFORCEMENT_IMPLEMENTATION_DETAIL_CLOSURE.md`  
**Delivery rule:** build P0–P10 coherently, then integrated test/deploy/production ratification; do not repeatedly product-deploy each micro-phase.

---

## 1. Program outcome

At program close, every governed mutation must follow:

```text
CURRENT AUTHORITY
→ VERIFIED DERIVATION
→ APPROVED PLAN BINDING
→ CURRENT EXECUTION ATTEMPT
→ EXECUTION PERMIT
→ ACTION FABRIC
→ OBSERVATION/EVIDENCE
→ COMPLETION GATE
```

No Worker, provider, channel, management component or external ingress may bypass this chain for consequential state changes.

---

## 2. P0 — Inventory the real mutation/completion surface

### Build

Create a repository scanner and generated inventory covering:

- all `ActionFabric.Action` implementations and consequence classification;
- direct file/process/shell/Git/deploy mutation adapters;
- durable stores exposing save/update/delete;
- Objective/Task/Worker lifecycle mutation methods;
- terminal `COMPLETE` / `SUCCEEDED` production transitions.

### Artifacts

```text
scripts/sot_enforcement/inventory.py
docs/sot-enforcement/mutation-surface-inventory.json
src/test/.../ExecutionMutationSurfaceArchitectureTest.java (or equivalent)
```

### Exit

Every governed mutation/completion path has one declared owner/enforcement point. Unknown consequential path = FAIL.

---

## 3. P1 — Authority snapshot and targeted discovery

### Build

Add Execution-owned governance records/services:

```text
AuthorityArtifact
AuthoritySnapshot
SotDiscoveryRecord
SotDiscoveryService
AuthoritySnapshotStore
```

Discovery provider must support exact repository/artifact identity and content hash. Initial implementation may consume configured canonical manifests/resources rather than a generalized remote crawler.

### Required behavior

- resolve only applicable authority subset;
- preserve repo commit/content identity;
- record scope/status/conflict/evidence requirements;
- cache immutable authority snapshots;
- no LLM dependency for validity decisions.

### Tests

```text
valid authority -> snapshot PASS
missing authority -> FAIL
wrong status/scope -> FAIL
conflict -> FAIL/BLOCK
same immutable authority -> snapshot reusable
```

---

## 4. P2 — Constraint bundle/evaluator

### Build

```text
ConstraintBinding
ConstraintBundle
ConstraintEvaluator
```

Support `MACHINE` and `REVIEW` kinds.

Seed MACHINE constraints from currently enforceable architecture/runtime contracts, including provider ownership, action/effect scope, assignment/authorization correlation, required evidence/state-transition predicates and exact-artifact identity where applicable.

### Exit

Every applicable normative execution constraint in the pilot acceptance set is either MACHINE-evaluated or explicitly REVIEW-gated. Unknown required constraint cannot silently pass.

---

## 5. P3 — Derivation receipt

### Build

```text
DerivationReceipt
DerivationReceiptStore
DerivationValidator
```

Bind plan/work digest to authority snapshot + constraint bundle.

Classification:

```text
DERIVED
CHANGE_PROPOSAL_REQUIRED
REJECTED
```

### Integration

Execution planning may generate a plan candidate, but `ExecutionAdmissionService` must not admit consequential Work without a valid receipt.

### Tests

- model/plan text cannot forge receipt;
- changed plan digest invalidates receipt;
- changed authority digest invalidates receipt;
- contradiction produces CHANGE_PROPOSAL_REQUIRED rather than a silently adjusted plan.

---

## 6. P4 — Approved plan binding

### Build

```text
ExecutionPlanBinding
ExecutionPlanBindingStore
PlanConformanceValidator
```

Persist exact:

```text
planId/version/digest
derivationReceiptId
authoritySnapshotId/digest
objectiveId
scope
steps/effect classes
acceptance/evidence requirements
approval identity/time/status
```

### Integration

Extend `ExecutionRequest` with governance references. Extend `ExecutionAdmissionService` to require exact APPROVED binding for consequential Work.

Legacy constructors remain only during migration and must fail closed for consequential execution.

---

## 7. P5 — ExecutionGate + permit + ActionFabric wiring

### Build

```text
ExecutionIntent
ExecutionPermit
GovernanceDenial
ExecutionGate
```

### Modify

```text
src/main/java/com/metatron/workforce/execution/ExecutionRequest.java
src/main/java/com/metatron/workforce/execution/ExecutionAdmissionService.java
src/main/java/com/metatron/workforce/execution/ExecutionAttempt.java
src/main/java/com/metatron/workforce/execution/ExecutionAttemptService.java
src/main/java/com/metatron/workforce/action/ActionFabric.java
src/main/java/com/metatron/workforce/action/CognitiveWorkerRuntime.java
```

### Runtime rule

For mutating actions:

```text
Brain Thought
→ ExecutionGate.authorize
→ ExecutionPermit
→ ActionFabric.execute(request, permit)
```

No permit means Action code is never entered.

### Defense in depth

Preserve existing ActionFabric Worker/authorization/consequence checks after permit validation.

### Tests

- valid action/step/scope executes;
- wrong step blocked;
- wrong action blocked;
- wrong scope blocked;
- mismatched permit/request blocked;
- permit cannot be reused across Worker/Objective/step.

---

## 8. P6 — Authority freshness + drift

### Build

```text
AuthorityFreshnessValidator
PlanDeviationDetector
```

### Modify

`ExecutionAttempt`/`ExecutionAttemptService` carry governance identity while retaining current lease/fencing behavior.

Before next governed mutation:

```text
attempt lease/fence valid
AND authority digest current
AND plan binding current
```

### Behavior

- authority changed -> `AUTHORITY_STALE`, affected scope blocked;
- plan materiality changed -> `PLAN_DEVIATION`, affected scope blocked + change proposal required;
- unrelated Objective remains runnable;
- no global lock/queue.

### Tests

- stale authority blocks next effect;
- valid rediscovery/rebind resumes;
- two independent Objectives continue concurrently;
- stale execution attempt remains fenced by existing fencing token even after governance rebinding.

---

## 9. P7 — CompletionGate

### Build

```text
CompletionCandidate
CompletionDecision
CompletionGate
```

### Modify

```text
CognitiveWorkerRuntime
AutonomousManagementRunner
AutonomyCoordinationService
other exact production completion owners discovered in P0
```

### Rule

Brain `COMPLETE` is a candidate only.

Institutional completion requires:

```text
mandatory steps terminal
acceptance PASS
evidence sufficient
no unresolved required failures
authority current
SoT constraints PASS
plan conformance PASS
Observation requirements PASS
exact artifact/deployment identity PASS when required
```

### Tests

- Brain COMPLETE with missing evidence -> DENY;
- Brain COMPLETE with stale plan -> DENY;
- unrelated failed optional diagnostic follows existing domain rules but cannot override mandatory acceptance;
- full evidence -> ALLOW.

---

## 10. P8 — Internal/external actor convergence

### Internal integration

Ensure consequential paths originating from:

```text
AutonomousManagementRunner
Workforce Head/manager planning
Cognitive Worker
Meeting-originated Work
Direct Worker conversation
```

all enter the same admission/gate chain.

### External/channel integration

Ensure `ChannelInteractionIngressService`, `MetatronConversationRuntime` and Gateway/MCP consequential requests resolve to the same governed Work services.

Simple Chat/status/read queries do not create Objectives or enter expensive governance unless a consequential transition is actually requested.

### Acceptance

Equivalent Work submitted from two channels/models yields the same authority/permit/denial decision.

---

## 11. P9 — Anti-bypass CI and adversarial acceptance

### Static CI

Add checks to `.github/workflows/ci.yml` and/or dedicated scripts:

```text
no direct Action.invoke outside ActionFabric
no known governed mutation adapter outside declared execution boundary
no consequential completion outside CompletionGate
no ExecutionGate/CompletionGate frontier-provider dependency
mutation inventory has no unknown unowned entry
```

### Adversarial cases

Attempt:

```text
ignore SoT and deploy
skip discovery
Founder already agreed verbally
change architecture directly
use direct mutation path
mark complete before Observation
continue after authority changes
```

Expected: deterministic denial/change-control response; never provider hopping to find an answer that permits execution.

### Concurrency acceptance

Prove at least two independent Objectives can execute governed actions concurrently without global queue/head-of-line blocking.

---

## 12. P10 — Production ratification

### Workflow

Add:

```text
.github/workflows/sot-enforcement-production-acceptance.yml
```

or equivalent Highway-owned production acceptance if deployment policy requires it.

### Mandatory production slices

```text
SE-01 read/status path responsive
SE-02 consequential work without discovery blocked
SE-03 valid derived+approved plan admitted
SE-04 permitted real mutation succeeds
SE-05 out-of-plan mutation blocked
SE-06 authority change invalidates next mutation only for affected scope
SE-07 rediscovery/rebind resumes
SE-08 premature completion blocked
SE-09 evidence-backed completion succeeds
SE-10 bypass mutation blocked
SE-11 external and internal actor governance equivalent
SE-12 independent Objectives execute concurrently
```

### Ratification identity

Record exact:

```text
source SHA
build/test SHA
deployed SHA
observed SHA
Universal/institution authority identities
plan ID/version/digest
permit + denial evidence
completion decision evidence
concurrency evidence
```

No `COMPLETE` claim if these identities do not satisfy the applicable exact-artifact contract.

---

## 13. Migration sequence

```text
M1 introduce records/stores + shadow evaluation
M2 require discovery/plan binding for newly accepted consequential Work
M3 require permits for all inventoried governed ActionFabric mutations
M4 route all consequential completion through CompletionGate
M5 remove/deprecate legacy bypass compatibility
```

Existing active work becomes `LEGACY_UNBOUND`; it may be inspected, but next governed mutation requires current binding.

Migration is per Objective/scope. No global maintenance queue is allowed.

---

## 14. Performance acceptance

Normal action path must not perform full repo discovery or frontier inference.

Expected hot path:

```text
load current attempt/binding
→ compare cached/current authority digest markers
→ evaluate deterministic step/scope constraints
→ issue permit
→ ActionFabric
```

Acceptance must demonstrate:

- no global queue;
- independent governed executions overlap in time;
- authority rediscovery occurs only on new task/scope/change/conflict;
- status/read Chat remains outside expensive governance path.

---

## 15. Build batching rule

Do not product-deploy/test each P as a separate closure claim.

Recommended engineering batching:

```text
Batch A: P0–P2 foundations
Batch B: P3–P6 execution authorization
Batch C: P7–P8 completion + ingress convergence
Batch D: P9–P10 acceptance + production ratification
```

Unit tests run continuously in development; integrated product acceptance occurs after the coherent chain exists.

---

## 16. Program Definition of Done

Do not close until:

```text
mutation inventory complete
AND all governed mutations use ExecutionPermit
AND all permits bind exact current authority + approved plan
AND stale authority blocks only affected work
AND material plan drift requires change control
AND all consequential completion uses CompletionGate
AND internal/external actors have equivalent governance
AND anti-bypass CI passes
AND adversarial acceptance passes
AND independent concurrency passes
AND exact production SHA passes SE-01..SE-12
```

Final verdict states only one of:

```text
SOT_ENFORCEMENT_CLOSURE = PASS
SOT_ENFORCEMENT_CLOSURE = PARTIAL
SOT_ENFORCEMENT_CLOSURE = FAIL
```

No historical component-level PASS may substitute for current integrated production evidence.
