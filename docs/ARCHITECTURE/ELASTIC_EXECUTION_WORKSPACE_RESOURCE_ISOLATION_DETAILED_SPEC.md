# Elastic Execution Workspace & Resource Isolation — Detailed Specification

Status: FOUNDER-APPROVED DERIVED SPECIFICATION — 2026-09-12
Parent: `ELASTIC_EXECUTION_WORKSPACE_RESOURCE_ISOLATION_GAP_CLOSURE.md`

## 1. Purpose and derivation rule

This document derives implementation contracts from the Founder-approved Final Proposal. It MUST NOT reinterpret ownership.

The following existing authorities remain canonical:

```text
Worker identity/state          -> WorkerActorRuntime / Workforce
Work eligibility/scheduling    -> AutonomySchedulingService
Attempt lifecycle/fencing      -> ExecutionAttemptService
Execution authorization        -> ExecutionGate / Governance
Actions                         -> ActionFabric
Repository credentials/effects -> Repository Control Plane
Release/deployment authority   -> Highway
```

New services exist only to close workspace/resource concurrency gaps.

## 2. Authority topology

```text
Work Graph
  |
  v
AutonomySchedulingService
  | eligible dispatch + worker allocation
  v
ExecutionAttemptService
  | canonical attempt identity/fence
  v
ExecutionResourceScheduler
  | infrastructure admission
  v
ExecutionResourceManager
  | resource ownership / resource fences
  v
ExecutionWorkspaceManager
  | attempt-owned mutable workspace
  v
ExecutionGate -> ActionFabric
  |
  v
RepositoryIntegrationController
  |
  v
Highway / Release Controller
```

No downstream service may grant authority that upstream governance did not grant.

## 3. Existing `ExecutionAttempt` contract

`ExecutionAttempt` remains the lifecycle record. Do not add a second attempt state machine.

Existing fields remain authoritative:

```text
attemptId
dispatchId
objectiveId
stepId
workerId
assignmentRef
authorizationRef
runtimeId
attemptNumber
fencingToken
status
leaseExpiresAt
heartbeatAt
checkpointRef
failure
createdAt
updatedAt
```

Existing attempt fence protects logical attempt freshness.

New services reference `attemptId` + `fencingToken`; they do not create a replacement `execution_id` lifecycle record.

Where generic APIs require an `executionId`, it aliases the canonical `attemptId` at the boundary unless a stronger upstream SOT later defines another identity.

## 4. ExecutionWorkspaceBinding

Proposed durable record:

```text
ExecutionWorkspaceBinding {
  workspaceId: String
  attemptId: String
  attemptFencingToken: long
  workerId: String
  objectiveId: String
  stepId: String
  rootPath: String
  stateVersion: long
  status: ALLOCATED | MATERIALIZING | READY | SEALED | RECOVERING | RETAINED | DISPOSED
  repositories: List<ExecutionRepositoryComponent>
  createdAt: Instant
  updatedAt: Instant
  sealedAt: Instant?
  retentionUntil: Instant?
}
```

Invariants:

- one active binding per active attempt;
- `rootPath` must be containment-checked beneath the configured execution root;
- binding creation requires a current, non-terminal attempt;
- mutation requires current attempt fencing token;
- terminal attempt may seal/retain/dispose but may not resume mutation without recovery creating/confirming an active attempt;
- state mutation is CAS on `stateVersion`.

## 5. ExecutionRepositoryComponent

```text
ExecutionRepositoryComponent {
  componentId: String
  repository: String
  requestedRef: String
  baseSha: String
  branchRef: String
  relativePath: String
  status: DECLARED | MATERIALIZING | READY | DIRTY | COMMITTED | PROPOSED | SEALED
  localBaselineSha: String?
  localHeadSha: String?
  pullRequestRef: String?
}
```

Rules:

- `repository` must pass `CanonicalRepositoryScope`;
- `baseSha` is exact 40-char immutable SHA before mutable repository actions;
- each component has its own base SHA;
- two components may not map to overlapping filesystem paths;
- component branch state is local to the attempt workspace;
- remote publication remains Repository Control Plane owned.

## 6. Workspace layout

Configured root:

```text
METATRON_EXECUTION_WORKSPACE_ROOT=/var/lib/metatron/executions
```

Canonical layout:

```text
<root>/<safe-attempt-key>/
  .metatron-execution-workspace
  metadata.json
  repos/
    <component-id>/
  build/
    <component-id>/
  cache-bindings/
  artifacts/
  evidence/
  logs/
```

`.metatron-execution-workspace` stores non-secret identity material sufficient to detect accidental workspace reattribution.

No credential values are written into metadata/evidence.

## 7. ExecutionWorkspaceManager API

Conceptual interface:

```text
allocate(attemptId, attemptFence) -> ExecutionWorkspaceBinding
get(attemptId) -> Optional<ExecutionWorkspaceBinding>
requireActive(attemptId, attemptFence) -> ExecutionWorkspaceBinding
registerRepository(attemptId, attemptFence, repository, requestedRef, componentId)
markMaterialized(..., resolvedBaseSha, localBaselineSha)
markDirty(...)
markCommitted(..., localHeadSha)
seal(attemptId, attemptFence)
recover(attemptId, attemptFence, recoveryPolicy)
retain(attemptId, retentionUntil)
dispose(attemptId, expectedWorkspaceVersion)
```

All mutators are idempotent or CAS-protected.

## 8. ObjectiveWorkspaceService migration

Current `ObjectiveWorkspaceService` keys by Objective + Worker and is used by `GeneralWorkspaceActionCatalog`, repository materialization, proposal publishing and sandbox invocation.

Migration target:

```text
ObjectiveWorkspaceService
   -> compatibility facade
      -> ExecutionWorkspaceManager
```

Compatibility rules during migration:

1. old callers without attempt context are not silently mapped to a shared Objective workspace for consequential mutation;
2. governed execution callers supply/bind current `attemptId`;
3. direct/operator repository mutation ingress must create/resolve an `ExecutionAttempt` before workspace allocation;
4. READ-only mirror inspection does not require this facade;
5. once all mutating callers are migrated, Objective+Worker keyed mutable allocation is retired.

## 9. Sandbox binding

`WorkerExecutionSandboxService` currently sends Worker/Object/Workspace identity. Target request adds canonical attempt identity:

```text
workerId
objectiveId
attemptId
attemptFencingToken
workspaceId
workingDirectory
executable
args
timeoutSeconds
maxOutputBytes
```

Sandbox response repeats:

```text
attemptId
workspaceId
workspaceKey/executionWorkspaceKey
executable
```

Workforce rejects attribution mismatch.

The sandbox receives no GitHub/LLM/Telegram/Workforce secret.

## 10. ResourceClaim

```text
ResourceClaim {
  claimId: String
  attemptId: String
  resourceId: String
  resourceClass: COMPUTE | REPOSITORY | BUILD | EXTERNAL | INTEGRATION | ENVIRONMENT
  mode: READ_SHARED | WRITE_EXCLUSIVE | LEASE_EXCLUSIVE | CAS_SERIALIZED | CAPACITY
  quantity: double
  unit: String
  required: boolean
  expectedVersion: String?
  metadata: bounded non-secret Map<String,String>
}
```

`resourceId` is canonical and stable. Examples:

```text
compute:cpu
compute:memory
sandbox:process
build:jvm
build:docker
github:api:mutation
repo:kelvinka38/metatron-workforce:read
branch:kelvinka38/metatron-workforce:metatron/objective-123
integration:kelvinka38/metatron-workforce:main
artifact:<sha-or-digest>
prod:workforce
```

## 11. ResourceLease

```text
ResourceLease {
  leaseId: String
  claimId: String
  resourceId: String
  attemptId: String
  ownerActor: String
  mode: ClaimMode
  fencingToken: long
  stateVersion: long
  acquiredAt: Instant
  expiresAt: Instant
  heartbeatAt: Instant
  status: ACTIVE | RELEASED | EXPIRED | FENCED
}
```

Per-resource fencing token is monotonic.

A lease's fencing token is never substituted for `ExecutionAttempt.fencingToken`.

## 12. ResourceFencingState

Durable per protected resource:

```text
ResourceFencingState {
  resourceId: String
  currentFencingToken: long
  stateVersion: long
  activeLeaseIds: Set<String>
  updatedAt: Instant
}
```

Exclusive acquisition atomically increments resource fencing generation.

Shared-read semantics may hold multiple active read leases without granting mutation rights.

## 13. ExecutionResourceManager API

```text
assess(attempt, claims) -> ResourceAssessment
acquire(attempt, claims, ttl) -> ResourceGrant
renew(attempt, attemptFence, leaseIds, ttl)
requireCurrent(attemptId, attemptFence, leaseId, resourceFence, resourceId)
release(attemptId, leaseIds)
reconcileExpired(now)
capacitySnapshot() -> ResourceCapacitySnapshot
```

`ResourceGrant` includes only references/tokens needed for enforcement; never credentials.

## 14. Protected effect validation

Any protected shared mutation uses a two-layer check:

```text
1. ExecutionGate / ExecutionAttemptService
   -> current attempt?
   -> current attempt fencing token?
   -> governance permit valid?

2. ExecutionResourceManager
   -> required resource lease active?
   -> resource fencing token current?
   -> expected version/SHA still current?
```

Typed failures:

```text
EXECUTION_ATTEMPT_FENCED
RESOURCE_LEASE_REQUIRED
RESOURCE_LEASE_EXPIRED
RESOURCE_FENCED
RESOURCE_CAPACITY_UNAVAILABLE
RESOURCE_VERSION_MISMATCH
STALE_BASE
```

## 15. ExecutionResourceScheduler

This scheduler is infrastructure-only.

Input record concept:

```text
ExecutionResourceAdmissionRequest {
  attemptId
  attemptFencingToken
  schedulingDecisionRef
  suppliedPriority
  deadline
  claims[]
  localityHints[]
}
```

Output:

```text
ADMITTED {
  grant
  executorRef
  workspaceRef
}

WAITING_RESOURCES {
  reason
  nextEligibleAt?
}

BLOCKED {
  typedReason
}
```

It MUST NOT plan Work or select unapproved actions.

## 16. Capacity model

Capacity is resource-class based rather than one global semaphore.

```text
ResourceCapacitySnapshot {
  resourceClass
  resourceId
  total
  used
  reserved
  available
  observedAt
  version
}
```

V1 may derive host capacity from configured safe ceilings rather than dynamically consume every free host byte/CPU. Configuration is operational capacity policy, not a Worker-count limit.

## 17. Fairness/backpressure

Minimum policy:

- no spawn when required capacity is unavailable;
- durable `WAITING_RESOURCES` state;
- per-objective concurrent-attempt ceiling;
- per-resource-class ceiling;
- aging prevents starvation;
- high-priority work may advance but cannot steal an already granted exclusive lease without explicit preemption protocol;
- no unbounded queue-to-process fanout.

## 18. Canonical mirror policy

Shared canonical mirror is safe only for non-consequential inspection.

Allowed:

```text
status/read/show/search/compare
read source for audit
fetch/update mirror through one controlled mirror maintainer if implemented
```

Forbidden normal path:

```text
switch branch for a Work item
edit files
clean/build producing shared outputs
commit
publish from local mirror state
deploy because mirror HEAD happens to equal target
```

Mutation requests must have an ExecutionAttempt and isolated workspace.

## 19. Repository materialization changes

Current `RepositoryWorkspaceMaterializationService` already resolves exact SHA. Generalize from one Objective-root repository to one component under an attempt workspace.

Requirements:

- preserve canonical repository allowlist;
- preserve no-credential-in-sandbox property;
- accept exact SHA without re-resolving a mutable branch when already supplied;
- record requested ref and resolved base SHA;
- safely support multiple components;
- no component may overwrite another;
- materialization retry is idempotent if provenance matches;
- mismatched existing provenance is `WORKSPACE_PROVENANCE_CONFLICT`.

## 20. GeneralWorkspaceActionCatalog binding

Actions keep their existing canonical refs. Do not fork an `execution.*` duplicate action surface.

The action catalog resolves current attempt workspace binding instead of provisioning by Objective+Worker directly.

Examples retained:

```text
workspace.repository.materialize
workspace.file.read/list/search/patch/write
workspace.dependencies.install
workspace.process.run
workspace.shell.run
workspace.git.status/diff/run
workspace.build.run
workspace.test.run
workspace.github.pr.publish
```

For cross-repo execution, mutable/file/process actions receive or derive a `componentId` / bounded working directory.

## 21. ConflictGraph

V1 model:

```text
ConflictGraph {
  executionNodes: attempts ready for integration
  edges: ConflictEdge[]
}

ConflictEdge {
  leftAttemptId
  rightAttemptId
  type: PATH_OVERLAP | RESOURCE_OVERLAP | PROTECTED_DOMAIN | BASE_STALE | GIT_CONFLICT
  repository?
  refs/evidence[]
}
```

The graph is evidence for scheduling/integration, not a replacement for Git CAS.

## 22. RepositoryIntegrationController

Responsibilities:

```text
register integration candidate
read exact current protected ref SHA
compare expected/base SHA
check conflict graph
require CI/acceptance evidence
queue candidate
acquire integration:<repo>:<branch> resource lease
perform CAS-governed merge through approved GitHub/Repository capability
record merge SHA
release resource
```

It cannot deploy production.

## 23. IntegrationQueueEntry

```text
IntegrationQueueEntry {
  entryId
  attemptId
  repository
  pullRequestRef
  expectedBaseSha
  candidateHeadSha
  status: QUEUED | VALIDATING | STALE | CONFLICTED | MERGING | MERGED | FAILED
  requiredEvidenceRefs[]
  createdAt
  updatedAt
}
```

If protected ref changes before merge, entry returns to stale/revalidation path.

## 24. Highway adapter contract

Highway retains release orchestration.

Add an adapter concept rather than a new release lock service:

```text
HighwayResourceClaimAdapter
  mapHighwayClaims(task) -> ResourceClaim[]
  acquireForRelease(attempt/release identity, claims)
  validateBeforeEffect(...)
  releaseAfterEffect(...)
```

Exact field mapping MUST be based on audit of Highway's live task/resource registry during P0/P9, not inferred from this document.

Migration requires dual evidence showing old Highway safety semantics and new canonical Resource Manager agree before old lock state is retired.

## 25. DeploymentExecution

Deployment remains Highway-owned but receives isolated execution/materialization semantics.

```text
DeploymentExecution {
  releaseId
  sourceRepository
  sourceSha
  artifactDigest?
  targetEnvironment
  resourceLeaseRefs[]
  deploymentWorkspaceRef?
  status
  verificationEvidenceRefs[]
}
```

Preferred mature flow uses immutable prebuilt artifact digest. Transitional accepted flow may materialize exact source SHA in a dedicated deployment workspace.

Never depend on unrelated canonical mirror/current checkout HEAD.

## 26. Artifact provenance

An immutable artifact record binds:

```text
source repository + source SHA
build recipe/version
artifact digest
build evidence
CI evidence
createdAt
```

Release selects artifact identity, not "whatever is in build/".

## 27. Idempotency

Canonical key:

```text
attemptId + actionRef + logicalSequence
```

Integration/release may extend with target identity:

```text
attemptId + actionRef + repository + expectedBaseSha + candidateHeadSha
releaseId + environment + artifactDigest
```

Idempotency lookup occurs before consequential retry.

## 28. Recovery and reconciliation

Attempt reconciliation remains `ExecutionAttemptService.reconcileExpired` authority.

New reconcilers:

```text
ResourceLeaseReconciler
WorkspaceReconciler
IntegrationQueueReconciler
DeploymentReconciler
```

Rules:

- resource lease cannot remain ACTIVE for terminal/fenced attempt beyond bounded grace;
- stale resource owner is fenced before regrant;
- workspace of ambiguous in-flight attempt is retained, not deleted;
- active attempt workspace is never GC'd;
- integration state is reconciled from immutable remote evidence, never guessed;
- release completion requires target verification evidence.

## 29. GC

Workspace GC eligibility requires all:

```text
attempt terminal
no active ResourceLease
not referenced by active recovery/integration/release
retentionUntil elapsed
workspace version unchanged since eligibility decision
```

GC uses CAS and records evidence.

## 30. Observability model

Expose secret-safe views:

```text
attemptId / status / fence / heartbeat
workspaceId / components / base SHAs / local heads / retention
resource claims / leases / expiry / resource fences
capacity totals/used/waiting
integration queue / stale/conflict reason
release/deploy resource ownership
```

Never expose repository tokens, sandbox tokens, OAuth tokens, provider credentials, or raw secret hashes as normal observability.

## 31. Security boundaries

- repository credential remains in Repository Control Plane / Workforce credential boundary;
- sandbox has no repository credential;
- resource tokens/fences are authorization-state references, not general-purpose secrets;
- path containment and symlink protections from existing workspace service are preserved;
- external client identity never grants repository authority by itself;
- resource lease never grants plan/authorization authority by itself;
- break-glass is explicit and separately audited.

## 32. Compatibility strategy

Migration is additive then convergent:

```text
existing code
 -> add attempt/workspace binding
 -> facade old ObjectiveWorkspaceService
 -> migrate all mutating callers
 -> enforce canonical mirror mutation denial
 -> remove old mutable Objective+Worker allocation behavior
```

No flag may leave two mutable workspace authorities indefinitely.

## 33. Failure taxonomy

Minimum typed failures:

```text
EXECUTION_ATTEMPT_REQUIRED
EXECUTION_ATTEMPT_FENCED
EXECUTION_WORKSPACE_REQUIRED
EXECUTION_WORKSPACE_STALE
WORKSPACE_PROVENANCE_CONFLICT
RESOURCE_CAPACITY_UNAVAILABLE
RESOURCE_LEASE_REQUIRED
RESOURCE_LEASE_EXPIRED
RESOURCE_FENCED
RESOURCE_VERSION_MISMATCH
RESOURCE_CONFLICT
STALE_BASE
INTEGRATION_CONFLICT
INTEGRATION_EVIDENCE_INCOMPLETE
DEPLOYMENT_RESOURCE_UNAVAILABLE
ARTIFACT_IDENTITY_MISMATCH
BREAK_GLASS_REQUIRED
```

Infrastructure blockers are not sent to cognition as generic coding failures unless recovery policy explicitly identifies a code-repair action.

## 34. Acceptance implementation mapping

```text
EE-01..03   ExecutionWorkspaceManager concurrency fixture
EE-04..10   Resource Manager + scheduler + fencing fixture
EE-11..14   integration/idempotency/recovery fixture
EE-15..20   mirror/repository/multi-repo/build isolation fixture
EE-21..23   scheduler/Highway convergence acceptance
EE-24..26   deployment isolation + immutable identity proof
EE-27..31   actor-scale/backpressure/GC/evidence/no-duplicate-authority proof
```

The most important acceptance test must execute two real concurrent same-repository clean/build operations and prove distinct paths, branches, outputs and unchanged peer state.
