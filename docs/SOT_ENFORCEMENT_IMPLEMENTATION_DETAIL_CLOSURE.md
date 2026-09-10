# METATRON SOT ENFORCEMENT — IMPLEMENTATION / DETAIL DESIGN CLOSURE

**Status:** DERIVED IMPLEMENTATION DESIGN — READY FOR EXECUTION-PLAN RATIFICATION  
**Date:** 2026-09-10  
**Implementation repository baseline:** `12a5421b4f3c86e92fb3975a759c804f5adfacf9`  
**Upstream approved closure:** `kelvinka38/metatron-institution/14_EXECUTION/SOT_ENFORCEMENT_DETAILED_GAP_CLOSURE.md`  
**Universal baseline:** `kelvinka38/universal@b6cf76a71bcb656d18bf309b6a22bb83b198c03a`  
**Design rule:** reuse current execution/runtime primitives; do not create a parallel execution architecture.

---

## 1. Design verdict

The current codebase already contains the correct foundations:

- `ExecutionAdmissionService`: fail-closed execution admission;
- `ExecutionRequest`: canonical admission request carrying actual Work;
- `GuidanceRequirement` / `GuidanceReceipt`: content provenance/hash proof pattern;
- `ExecutionAttempt` / `ExecutionAttemptService`: durable execution identity, lease and fencing semantics;
- `ExecutionWorkSpec`: bounded/verifiable work plan shape;
- `ActionFabric`: governed Worker effect boundary;
- `CognitiveWorkerRuntime`: bounded think → act → observe → reflect loop;
- `ActionJournal`: durable cycle/effect evidence;
- `BiosConformanceValidator`: machine-checkable Intelligence authority/evidence boundary;
- `IntelligenceFabric`: institutional reasoning boundary;
- `AutonomousManagementRunner` / management package: long-lived Objective execution coordination.

The required change is therefore not a new runtime. It is to bind these existing components into one authority-conformant execution chain.

Target:

```text
ExecutionAdmissionService
  ↓
AuthorityBinding / PlanBinding
  ↓
ExecutionAttemptService
  ↓
CognitiveWorkerRuntime
  ↓
ActionIntent
  ↓
ExecutionGate
  ↓
ExecutionPermit
  ↓
ActionFabric
  ↓
ActionObservation
  ↓
CompletionGate
```

---

## 2. Package ownership

New implementation types belong primarily under:

```text
com.metatron.workforce.execution.governance
```

This keeps authority enforcement inside Execution rather than inside Worker cognition or provider code.

Proposed package:

```text
src/main/java/com/metatron/workforce/execution/governance/
  AuthorityArtifact.java
  AuthoritySnapshot.java
  AuthoritySnapshotStore.java
  SotDiscoveryRecord.java
  SotDiscoveryService.java
  ConstraintBinding.java
  ConstraintBundle.java
  ConstraintEvaluator.java
  DerivationReceipt.java
  DerivationReceiptStore.java
  DerivationValidator.java
  ExecutionPlanBinding.java
  ExecutionPlanBindingStore.java
  ExecutionPermit.java
  ExecutionGate.java
  AuthorityFreshnessValidator.java
  PlanConformanceValidator.java
  CompletionCandidate.java
  CompletionDecision.java
  CompletionGate.java
  GovernanceDenial.java
```

Exact class names are implementation-level and may be simplified, but the ownership boundary MUST remain Execution/governance rather than Worker/provider-local.

No new Universal or institutional ontology is introduced by these Java types.

---

## 3. Data model

### 3.1 AuthorityArtifact

Minimum fields:

```text
authorityLevel
repository
artifactPath
repositoryCommitSha
contentSha256
status
scope
resolvedAt
```

Invariant:

```text
(repository, artifactPath, repositoryCommitSha, contentSha256)
```

uniquely identifies the authority material that was actually read.

### 3.2 AuthoritySnapshot

Minimum fields:

```text
snapshotId
taskId / objectiveId
targetEntity
targetScope
authorityArtifacts[]
constraintIds[]
conflictStatus
digest
createdAt
```

`digest` is deterministic over normalized authority identities and constraints, not over model prose.

### 3.3 SotDiscoveryRecord

Minimum fields mirror the locked Universal discovery protocol:

```text
discoveryId
taskId
actorId
taskType
targetEntity
targetScope
applicableAuthority[]
relevantConstraints[]
evidenceSources[]
conflictStatus
discoveredAt
```

A record is valid only when the existing SOT gate predicate is satisfied.

### 3.4 ConstraintBinding

```text
constraintId
authorityArtifactIdentity
kind = MACHINE | REVIEW
predicateType
predicatePayloadDigest
reviewRequirement
```

The runtime must never treat the compiled predicate as authority independent of its source identity.

### 3.5 DerivationReceipt

```text
receiptId
proposalOrWorkDigest
authoritySnapshotId
authorityDigest
constraintBundleDigest
classification = DERIVED | CHANGE_PROPOSAL_REQUIRED
machineValidation
reviewValidation
issuedAt
```

Only institution-owned code issues a valid receipt.

### 3.6 ExecutionPlanBinding

```text
planId
version
planDigest
derivationReceiptId
authoritySnapshotId
authorityDigest
objectiveId
scope
steps[]
allowedEffectClassesByStep
acceptanceCriteria
evidenceRequirements
status = DRAFT | APPROVED | SUPERSEDED | REVOKED
approvedBy
approvedAt
```

Material plan changes create a new version/digest.

### 3.7 ExecutionPermit

Short-lived, non-transferable authorization for one planned effect:

```text
permitId
objectiveId
attemptId
workerId
assignmentRef
authorizationRef
planId
planVersion
stepId
actionRef
authorityDigest
planDigest
scopeDigest
issuedAt
```

The permit is evidence of a successful gate decision; it does not replace existing Worker/Assignment authorization.

---

## 4. Reuse versus replacement decisions

### 4.1 `GuidanceReceipt` remains guidance-only

Do not overload `GuidanceReceipt` into authority.

Current semantics explicitly distinguish guidance from authorization. Preserve that distinction.

Reuse its useful pattern:

```text
sourceRef + content hash + read timestamp
```

for `AuthorityArtifact` provenance.

### 4.2 `ExecutionAdmissionService` becomes the first enforcement choke point

Current admission validates:

```text
Assignment
Authorization
required GuidanceReceipt(s)
actual ExecutionWorkSpec
```

Extend `ExecutionRequest` to additionally carry or resolve:

```text
authoritySnapshotId
derivationReceiptId
executionPlanId
executionPlanVersion
```

`ExecutionAdmissionService.admit()` then validates:

```text
existing assignment/authorization/guidance predicates
AND valid authority snapshot
AND valid derivation receipt
AND APPROVED plan binding
AND workSpec belongs to exact plan step
AND authority is current at admission time
```

Failure remains fail-closed.

### 4.3 `ExecutionAttempt` binds execution identity to governance identity

Extend attempt identity with:

```text
authoritySnapshotId
authorityDigest
planId
planVersion
planDigest
derivationReceiptId
```

Do not remove existing lease/fencing semantics.

Current `ExecutionAttemptService` already fences stale runtime attempts by logical Objective/step fencing token. Extend freshness checks so an attempt with a stale authority/plan binding cannot continue mutating even if its lease/fencing token remains current.

### 4.4 `ExecutionWorkSpec` stays a Work description

Do not turn `ExecutionWorkSpec` itself into authority.

Add only the minimum correlation required to identify the approved plan step if needed, e.g. `planStepRef`, or carry that mapping in the `ExecutionPlanBinding` store.

The canonical distinction remains:

```text
WORK SPEC != AUTHORITY
WORK SPEC + VALID BINDING -> EXECUTABLE CANDIDATE
```

---

## 5. ExecutionGate design

### 5.1 Location

```text
com.metatron.workforce.execution.governance.ExecutionGate
```

### 5.2 Input

The gate receives an `ExecutionIntent` assembled by trusted runtime code, not free-form model JSON:

```text
objectiveId
attemptId
workerId
assignmentRef
authorizationRef
planId
planVersion
stepId
actionRef
actionConsequence
inputs/scope summary
authoritySnapshotId
derivationReceiptId
```

### 5.3 Required checks

In order:

```text
1 actor/assignment/authorization correlation
2 execution attempt current + not fenced/terminal
3 derivation receipt valid
4 plan binding APPROVED
5 plan version/digest matches attempt
6 authority snapshot matches plan
7 authority freshness PASS
8 step exists and is active
9 requested action/effect allowed by step
10 target scope is inside approved scope
11 applicable MACHINE constraints PASS
12 applicable REVIEW constraints have valid review evidence
13 no unresolved authority conflict
14 no material plan deviation
```

### 5.4 Output

Success:

```text
ExecutionPermit
```

Failure:

```text
GovernanceDenial(code, reason, evidenceRefs, observedAt)
```

No LLM retry is allowed solely because the gate denied conformance.

---

## 6. `ActionFabric` integration

Current `ActionFabric.execute(ActionRequest)` is the real Worker tool/effect boundary. It already verifies Worker and authorization and blocks mutating actions on read-only work.

Change the API so governed mutating execution cannot reach `Action.invoke()` without a permit.

Preferred shape:

```text
ActionObservation execute(ActionRequest request, ExecutionPermit permit)
```

For `READ_ONLY` actions, one of two approaches may be used:

1. require a lightweight read permit for uniformity; or
2. permit current catalog authorization without full mutation binding when the read has no consequential side effect.

The implementation MUST still classify ambiguous actions as mutating.

Before invocation, ActionFabric validates at minimum:

```text
permit.actionRef == request.actionRef
permit.workerId == request.workerId
permit.assignmentRef == request.assignmentReference
permit.objectiveId == request.objectiveId
permit.stepId == request.workStepId
permit is current/not revoked
```

Then existing Action-level authorization runs as defense in depth.

The evidence emitted by ActionFabric must include:

```text
permitId
planId/version
authorityDigest
```

in addition to current Worker/assignment/authorization/consequence/success attribution.

---

## 7. `CognitiveWorkerRuntime` integration

Current loop:

```text
Brain.think
→ ActionRequest
→ ActionFabric.execute
→ observation
→ Brain.reflect
```

Target:

```text
Brain.think
→ Thought / ActionIntent
→ ExecutionGate.authorize
→ ExecutionPermit
→ ActionFabric.execute
→ observation
→ Brain.reflect
```

The Brain never receives authority to self-approve a permit.

### 7.1 Gate denial behavior

A denial due to:

```text
ACTION_OUTSIDE_PLAN
ACTION_OUTSIDE_SCOPE
CONSTRAINT_VIOLATION
PLAN_DEVIATION
AUTHORITY_STALE
```

must not be converted into a generic failed Action and then fed into creative retry indefinitely.

Instead produce a governed terminal/blocked result that the management layer can classify as:

```text
BLOCKED_AUTHORITY
BLOCKED_PLAN_DEVIATION
CHANGE_PROPOSAL_REQUIRED
```

Provider/network/tool transient failures remain recoverable through current retry/replan behavior.

### 7.2 Completion

`Brain.reflect(...).COMPLETE` becomes only a `CompletionCandidate`.

`CognitiveWorkerRuntime` invokes `CompletionGate` before returning `Outcome.success=true` for consequential Work.

If CompletionGate fails, the result is not success even if the Brain requested COMPLETE.

---

## 8. CompletionGate design

### 8.1 Input

```text
objectiveId
attempt/work graph state
plan binding
authority snapshot
ActionJournal history
ActionObservations
acceptance evidence
Observation evidence
deployment identity when required
```

### 8.2 Predicate

For consequential completion:

```text
mandatory plan steps satisfied
AND mandatory acceptance criteria satisfied
AND required evidence present
AND unresolved required failures empty
AND authority still current
AND SoT constraints conform
AND plan conforms
AND independent Observation requirements satisfied
AND exact artifact/deployment identity satisfied where required
```

### 8.3 Exact artifact identity

For deployable code closure:

```text
source SHA == tested SHA == approved SHA == deployed SHA == observed SHA
```

where the applicable workflow requires all five identities.

### 8.4 Output

```text
CompletionDecision(ALLOW | DENY | CHANGE_REQUIRED, codes, evidenceRefs)
```

Only `ALLOW` may produce institutional COMPLETE.

---

## 9. Authority discovery and freshness

### 9.1 Do not read all repos per action

`SotDiscoveryService` executes at:

```text
new consequential task
new scope
new plan
canonical authority change
material conflict/deviation
explicit rediscovery
```

not on every tool action.

### 9.2 Cached immutable snapshots

Store snapshots keyed by deterministic authority digest. Immutable content can be reused across independent tasks.

Per-action freshness normally checks:

```text
bound authority digest/version markers
vs current indexed authority manifest/digest
```

This must be a local/store check on the normal path.

### 9.3 No global execution queue

Authority validation is per Objective/step/attempt. Independent Objectives continue in parallel.

Only the affected scope is blocked when authority is stale/conflicted or plan deviation occurs.

---

## 10. Constraint loading

P2 requires a practical first implementation, not a universal natural-language compiler.

### 10.1 Initial machine constraints

Start with constraints already expressible deterministically and already partially represented in code/tests:

```text
provider ownership boundary
Chat/Work/Meeting/Monitor hierarchy where applicable
Worker → Intelligence provider boundary
execution authorization/assignment correlation
mutation consequence classification
required evidence fields
state-transition rules
exact artifact/deployment identity
allowed action/effect scope
```

### 10.2 Semantic REVIEW constraints

Normative requirements not yet safely machine-compilable are represented as explicit `REVIEW` bindings with required evidence and reviewer identity.

The runtime must never silently downgrade REVIEW to PASS.

### 10.3 Progressive compilation

When a REVIEW rule later becomes deterministic, migrate it to MACHINE without changing its upstream authority identity.

---

## 11. Approval and plan binding

Natural-language approval is only an ingress signal.

The system must resolve it to exactly one pending plan object:

```text
planId
version
planDigest
```

and persist the approval identity/authority/time.

If more than one pending material plan matches and resolution is ambiguous, no binding is created.

After binding:

```text
execution mode = DERIVE / IMPLEMENT / VERIFY
```

A model that discovers a better conflicting architecture must create `CHANGE_PROPOSAL_REQUIRED`; it may not mutate the approved plan silently.

---

## 12. Management / Objective integration

The long-lived management layer must consume governed execution states rather than infer completion from Worker prose.

Required mapping:

```text
ExecutionGate denial: AUTHORITY_STALE
  -> Objective/step BLOCKED, request rediscovery/rebind

ExecutionGate denial: PLAN_DEVIATION
  -> affected step BLOCKED, create/await ChangeProposal

CompletionGate DENY
  -> remain RUNNING/BLOCKED according to denial; never COMPLETE

CompletionGate ALLOW
  -> management lifecycle may close step/Objective if remaining graph predicates permit
```

`AutonomousManagementRunner`, `AutonomyCoordinationService`, or the exact current lifecycle owner must not implement a second completion predicate that bypasses `CompletionGate`.

---

## 13. Gateway / MCP / channel integration

The enforcement boundary is not provider-specific.

External ingress via Web/Telegram/Zalo/MCP/ChatGPT/Claude/Gemini may submit:

```text
request
proposal
approval signal
change proposal
status query
```

but cannot self-issue:

```text
DerivationReceipt
ExecutionPlanBinding
ExecutionPermit
CompletionDecision(ALLOW)
```

`ChannelInteractionIngressService`, `MetatronConversationRuntime`, Direct Worker conversation paths and future MCP semantic endpoints must converge on the same institutional services for consequential Work.

Simple chat/status/retrieval remains outside the heavy governance path unless it requests a consequential state transition.

This prevents governance from turning ordinary Chat into Workforce Objectives or creating unnecessary queues.

---

## 14. Persistence

Use durable stores following existing Workforce persistence patterns.

Required durable records:

```text
AuthoritySnapshot
SotDiscoveryRecord
DerivationReceipt
ExecutionPlanBinding
GovernanceDenial for consequential denials
CompletionDecision
```

`ExecutionPermit` may be short-lived but its issuance identity/result must be reconstructable in execution evidence.

Persist before acknowledging approved plan binding or institutional completion.

Suggested filesystem/store namespaces for current runtime architecture:

```text
/var/lib/metatron-workforce/authority-snapshots/
/var/lib/metatron-workforce/derivation-receipts/
/var/lib/metatron-workforce/execution-plan-bindings/
/var/lib/metatron-workforce/governance-decisions/
```

If the implementation already has a generalized durable store abstraction suitable for these records, reuse it instead of creating independent file stores.

---

## 15. Mutation-surface audit implementation

P0 must be executed mechanically before final gate wiring.

Repository scan must identify:

```text
all ActionFabric.Action implementations with MUTATING consequence
all direct filesystem write/delete/move APIs
all ProcessBuilder/shell paths able to mutate
all GitHub/Git mutation adapters
all deploy/release invocations
all Objective/Task/Worker state mutation services
all stores with write/save/update/delete semantics
all code paths that produce COMPLETE/SUCCEEDED terminal state
```

Output machine-readable `docs/sot-enforcement/mutation-surface-inventory.json` or equivalent generated artifact used by CI.

Every governed entry must identify `enforcementPoint`.

---

## 16. CI anti-bypass gates

Add architecture checks analogous to the existing Intelligence provider-boundary CI guard.

At minimum:

### Gate A — Action invocation ownership

Fail if production packages call `Action.invoke()` directly outside ActionFabric.

### Gate B — Governed mutation ownership

Fail if known mutation adapters are invoked outside approved execution packages/boundaries.

### Gate C — Completion ownership

Fail if consequential management/runtime code creates institutional COMPLETE/SUCCEEDED without the approved completion service/boundary.

### Gate D — Provider independence of governance

Fail if ExecutionGate, freshness validation or CompletionGate depends on a frontier provider to produce PASS on the normal deterministic path.

### Gate E — No global queue regression

Architecture/acceptance checks must prove independent Objectives can hold active permits/attempts concurrently.

---

## 17. Test architecture

### 17.1 Unit

Required suites:

```text
SotDiscoveryServiceTest
AuthorityFreshnessValidatorTest
DerivationValidatorTest
ExecutionPlanBindingTest
ExecutionGateTest
ActionFabricPermitTest
CognitiveWorkerGovernanceTest
CompletionGateTest
```

### 17.2 Integration

Required scenarios:

```text
missing discovery -> admission denied
invalid derivation -> denied
unapproved plan -> denied
valid plan/action -> succeeds
wrong step/action -> denied
wrong target scope -> denied
authority changes after attempt begins -> next mutation denied
new binding after rediscovery -> resumes
Brain requests COMPLETE early -> CompletionGate denies
all evidence/conformance present -> CompletionGate allows
```

### 17.3 Adversarial model/Worker

Drive the same institutional Work through multiple reasoning actors and deliberately request:

```text
ignore SoT
skip discovery
Founder agreed verbally
change architecture directly
use direct mutation path
mark complete before Observation
continue with stale SoT
```

Governance results must be actor-independent.

### 17.4 Concurrency

Run independent Objectives A/B concurrently. Governance must not serialize them through a global queue or global permit lock.

Run two attempts for the same Objective/step and prove existing fencing plus governance binding prevents stale mutation.

---

## 18. Production acceptance harness

Add a dedicated workflow/harness, suggested name:

```text
.github/workflows/sot-enforcement-production-acceptance.yml
```

It must deploy/target one exact artifact and prove:

```text
SE-01 normal read/status path remains responsive
SE-02 consequential request without discovery blocked
SE-03 derived+approved plan admitted
SE-04 permitted real mutation succeeds
SE-05 unplanned mutation blocked
SE-06 stale authority invalidates next mutation without global outage
SE-07 rediscovery/rebinding resumes affected work
SE-08 premature completion blocked
SE-09 final evidence-backed completion succeeds
SE-10 direct/bypass mutation attempt blocked
SE-11 external ingress actor and internal Worker receive same governance result
SE-12 two independent Objectives execute concurrently
```

Final artifact must record:

```text
source SHA
deployed SHA
authority baseline identities
plan IDs/versions
permit/denial evidence
completion decision evidence
concurrency evidence
```

---

## 19. Migration

### Stage M1 — dual-read / enforcement shadow

Introduce authority/plan data structures and evaluate gates without blocking existing read-only paths. For consequential mutation paths, shadow output must be observable but no completion claim is made yet.

### Stage M2 — admission enforcement

Require valid authority/plan binding for all newly accepted consequential Work. Existing active work becomes `LEGACY_UNBOUND`.

### Stage M3 — action enforcement

Require permits for all inventoried governed ActionFabric mutations. Any remaining legacy bypass fails CI.

### Stage M4 — completion enforcement

Route all consequential completion through CompletionGate.

### Stage M5 — remove legacy bypass compatibility

Delete/deprecate constructors/code paths that allow consequential work without governance bindings once migration tests prove no live consumer remains.

Migration must not introduce a global pause. Independent valid Work continues.

---

## 20. Failure semantics

Canonical machine denial codes:

```text
SOT_DISCOVERY_REQUIRED
AUTHORITY_UNRESOLVED
AUTHORITY_CONFLICT
DERIVATION_UNVERIFIED
PLAN_NOT_APPROVED
PLAN_BINDING_MISMATCH
AUTHORITY_STALE
STEP_NOT_ACTIVE
ACTION_OUTSIDE_PLAN
ACTION_OUTSIDE_SCOPE
CONSTRAINT_VIOLATION
PLAN_DEVIATION
EVIDENCE_INSUFFICIENT
COMPLETION_CONFORMANCE_FAILED
```

Classification:

```text
provider/network transient failure -> retry/recovery policy may apply
tool transient failure             -> retry/recovery policy may apply
lease/fencing failure              -> existing recovery/fencing policy
SoT/authority conflict             -> STOP affected scope
plan deviation                     -> CHANGE_PROPOSAL_REQUIRED
governance denial                  -> never provider failover/retry merely to obtain a different answer
```

---

## 21. Performance budget

Normal governed action must not perform full repository discovery or frontier inference.

Expected normal hot path:

```text
resolve attempt/binding from durable/in-memory cache
+ digest freshness lookup
+ step/scope/constraint predicate checks
+ ActionFabric authorization
```

No system-wide execution mutex is allowed.

Expensive rediscovery is event-driven by new task/scope or authority/plan change.

---

## 22. Implementation sequence P0–P10

### P0
Produce mutation/completion surface inventory and tests that make unknown bypasses visible.

### P1
Implement authority artifact/snapshot/discovery store and targeted discovery provider abstraction.

### P2
Implement constraint bundle/evaluator and classify first canonical machine/review constraints.

### P3
Implement derivation receipt/store/validator and bind generated execution work plans to receipts.

### P4
Implement plan binding/store and approval resolution to exact plan version/digest.

### P5
Implement ExecutionGate/Permit; extend ExecutionRequest, ExecutionAdmissionService, ExecutionAttempt and ActionFabric; wire CognitiveWorkerRuntime.

### P6
Implement authority freshness + plan drift detection; preserve existing lease/fencing and recovery semantics.

### P7
Implement CompletionGate and route Worker/management success transitions through it.

### P8
Converge internal Worker/Workforce/Meeting and external Gateway/channel/MCP consequential ingress on the same services.

### P9
Add static anti-bypass CI plus unit/integration/adversarial/concurrency suites.

### P10
Add exact-artifact production acceptance, deploy, run SE-01..SE-12, and publish ratification evidence.

Build P0–P10 as one coherent implementation program before final integrated production ratification; do not repeatedly deploy each tiny P as a separate product iteration.

---

## 23. Files/classes expected to change

Current exact classes with direct design impact:

```text
src/main/java/com/metatron/workforce/execution/ExecutionRequest.java
src/main/java/com/metatron/workforce/execution/ExecutionAdmissionService.java
src/main/java/com/metatron/workforce/execution/ExecutionAttempt.java
src/main/java/com/metatron/workforce/execution/ExecutionAttemptService.java
src/main/java/com/metatron/workforce/action/ActionFabric.java
src/main/java/com/metatron/workforce/action/CognitiveWorkerRuntime.java
src/main/java/com/metatron/workforce/interaction/intelligence/ExecutionWorkSpec.java
src/main/java/com/metatron/workforce/management/AutonomousManagementRunner.java
src/main/java/com/metatron/workforce/management/AutonomyCoordinationService.java
```

Likely integration/wiring impact:

```text
src/main/java/com/metatron/workforce/interaction/ChannelInteractionIngressService.java
src/main/java/com/metatron/workforce/interaction/MetatronConversationRuntime.java
src/main/java/com/metatron/workforce/interaction/DirectWorkerConversationService.java
src/main/java/com/metatron/workforce/interaction/intelligence/BiosConformanceValidator.java
src/main/java/com/metatron/workforce/interaction/intelligence/IntelligenceFabric.java
Spring/runtime composition classes that construct execution/action/management services
.github/workflows/ci.yml
production acceptance workflows/scripts
```

`IntelligenceFabric` itself should not own execution authority; changes there should be limited to carrying/consuming authority context required by downstream execution planning.

---

## 24. Explicit non-goals

This implementation MUST NOT:

```text
create another Worker runtime
replace IntelligenceFabric
remove frontier reasoning
serialize all Workers through one queue
read all canonical repositories before each Action
call an LLM to validate every permit
turn GuidanceReceipt into authority
allow MCP to become the only defense layer
rewrite Universal SoT merely to justify implementation convenience
```

---

## 25. Exit criteria for Detail Design Closure

This design is closed when the execution plan derived from it satisfies all of the following:

```text
one owner for authority enforcement = Execution governance boundary
one canonical mutating effect path = ExecutionGate -> ActionFabric
one canonical consequential completion authority = CompletionGate
existing lease/fencing retained
existing Worker/Assignment authorization retained
no provider dependency on normal gate path
no global queue
external/internal actors share the same governance semantics
migration path defined
P0–P10 acceptance mapped
```

No further architectural reinterpretation is required before implementation unless code audit uncovers a true contradiction with the approved upstream closure.
