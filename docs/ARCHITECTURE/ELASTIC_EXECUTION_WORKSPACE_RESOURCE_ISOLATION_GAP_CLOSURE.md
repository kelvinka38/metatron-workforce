# METATRON — Elastic Execution Workspace & Resource Isolation

Status: FOUNDER-APPROVED FINAL PROPOSAL / CANONICAL GAP CLOSURE CONTRACT — 2026-09-12

## 1. Objective

Close the concurrency/scalability gap that allows otherwise independent Workers, AI operators, Human engineering sessions, builds, repository mutations, integration work, or release work to interfere through shared mutable checkout, branch/HEAD, build output, or deployment-source state.

This program is downstream of the accepted Worker/Autonomy/Execution/Repository Control Plane architecture. It MUST extend existing institutional authorities rather than create replacements.

Target:

```text
many durable Workers
        +
existing governed ExecutionAttempt authority
        +
capacity-bounded infrastructure execution
        +
per-attempt mutable workspace isolation
        +
resource claims / resource leases / fencing
        +
CAS-protected integration
        +
immutable release/deployment
```

## 2. Incident that opened this gap

Two legitimate agent activities operated against the same mutable physical checkout:

```text
/opt/metatron/metatron-workforce
        +
same Git checkout
        +
same current branch / HEAD
        +
same build/
        +
same local deployment source
```

Observed failure classes:

```text
Agent A clean/build
-> Agent B loses build/test artifacts

Agent B switches branch
-> Agent A observes branch/HEAD changing underneath it

Agent A prepares deploy SHA X
-> Agent B changes local HEAD to Y
-> deploy safeguard rejects X because HEAD != requested SHA
```

This is not a Worker identity or deliberation bug. It is an Execution Workspace and protected-resource ownership gap.

The mandatory regression proof is therefore:

```text
Agent A clean/build
+
Agent B clean/build
+
same repository
+
simultaneous execution
-> different mutable workspaces
-> different branch state
-> different build outputs
-> zero cross-agent interference
```

## 3. Non-negotiable architecture laws

```text
WORKER IDENTITY != EXECUTION ATTEMPT
EXECUTION ATTEMPT != EXECUTION WORKSPACE
WORKER COUNT != EXECUTION CONCURRENCY
EXECUTION CONCURRENCY != BUILD CONCURRENCY
BUILD CONCURRENCY != MERGE CONCURRENCY
MERGE CONCURRENCY != DEPLOY CONCURRENCY

ONE ACTIVE EXECUTION ATTEMPT -> ONE MUTABLE WORKSPACE OWNER
EVERY REPOSITORY COMPONENT STARTS FROM AN IMMUTABLE BASE SHA
NO NORMAL ACTOR MUTATES THE CANONICAL CHECKOUT/MIRROR
READ-ONLY CANONICAL MIRROR INSPECTION MAY REMAIN SHARED
SHARED MUTATIONS REQUIRE GOVERNED RESOURCE OWNERSHIP
EXECUTION FENCING != RESOURCE FENCING
LEASE WITHOUT FENCING IS INSUFFICIENT
SHARED STATE MUTATION REQUIRES EXPECTED VERSION / CAS
MERGE AUTHORITY != CODING AUTHORITY
DEPLOY AUTHORITY != MERGE AUTHORITY
PRODUCTION DEPLOYMENT OPERATES ON IMMUTABLE IDENTITY
RETRY MUST NOT DUPLICATE CONSEQUENTIAL EFFECTS
EXECUTOR FAILURE MUST NOT STRAND RESOURCE OWNERSHIP
EXTERNAL AI AND INTERNAL WORKERS SHARE THE SAME MUTATION-ISOLATION MODEL
```

No hard-coded Worker-count ceiling is introduced.

## 4. Amendment A1 — ExecutionAttempt remains the execution-lifecycle authority

Current code already has `ExecutionAttempt` / `ExecutionAttemptService` with:

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
```

Current states include:

```text
LEASED
RUNNING
SUCCEEDED
FAILED
ABANDONED
FENCED
```

`ExecutionAttemptService` already owns begin, heartbeat, checkpoint, expiry reconciliation and stale-attempt fencing. `ExecutionGate` already validates the current execution-attempt fencing token before mutating Actions.

Therefore this program MUST NOT create a second execution-lifecycle source of truth.

The lightweight existing `com.metatron.workforce.execution.ExecutionRecord` is not promoted into a competing lifecycle model. Any historical/compatibility Execution record remains an identity/compatibility artifact unless separately migrated under governed change control.

New isolation state binds to the existing attempt:

```text
ExecutionAttempt
   +-- ExecutionWorkspaceBinding
   +-- ResourceClaim[]
   +-- ResourceLease[]
   +-- integration/release evidence refs
```

## 5. Amendment A2 — ExecutionAttempt fencing and ResourceLease fencing are distinct

Existing attempt fencing protects ownership of the logical Objective/step attempt.

New resource leases protect concrete shared resources.

```text
ExecutionAttempt
  fencingToken = attempt lifetime / stale executor protection

ResourceLease
  resourceFencingToken = protected resource ownership generation
```

One attempt may hold zero, one, or many resource leases.

A protected shared effect MUST validate both where applicable:

```text
current ExecutionAttempt + current attempt fencing token
AND
current ResourceLease + current resource fencing token
```

Example:

```text
attempt fence = 17
resource prod:workforce fence = 42
```

These tokens MUST NOT be collapsed into one number or one lease universe.

## 6. Amendment A3 — no second work scheduler

`AutonomySchedulingService` remains upstream institutional work scheduling policy. It already reasons over ready Work, capability, Worker capacity, bounded parallelism, budget, deadline and risk.

New infrastructure scheduling sits below it and is named explicitly:

```text
Objective / Work Graph
        |
        v
AutonomySchedulingService
  WHAT is eligible
  priority / worker allocation
  policy / risk / budget
        |
        v
ExecutionAttempt
        |
        v
ExecutionResourceScheduler
  WHEN eligible attempt receives infra capacity
  WHERE it executes
  WHICH resource claims can be granted
        |
        v
ExecutionResourceManager
```

`ExecutionResourceScheduler` MUST NOT reinterpret business/work eligibility or become a second Management scheduler.

## 7. Amendment A4 — Highway resource claims converge into the same resource model

Release authority remains Highway. This program MUST NOT create a second release plane.

Highway's existing resource semantics such as source/artifact/production/GitHub claims are to be adapted into the canonical `ExecutionResourceManager` model rather than maintained as an independent lock universe.

Target:

```text
Highway release/task resource claims
              |
          adapter
              v
ExecutionResourceManager
              |
 canonical resource ownership/fencing state
```

Release control remains:

```text
Highway / Release Controller
```

Resource ownership semantics/source-of-truth converge.

Until the Highway task-registry implementation is directly inspected in this implementation thread, exact adapter field mapping is an implementation-audit task and MUST NOT be guessed.

## 8. Amendment A5 — multi-repository workspace is first-class

An attempt may operate across multiple canonical repositories. Workspace identity is still one attempt owner, but repository components are independent immutable bases.

Canonical layout:

```text
/var/lib/metatron/executions/<attempt-id>/
  metadata.json
  repos/
    workforce/
    institution/
    universal/
    bios/
  build/
  cache-bindings/
  artifacts/
  evidence/
  logs/
```

Metadata contains:

```text
attempt_id
worker_id
objective_id
step_id
workspace_id
workspace_version

repositories[] {
  repository
  base_sha
  requested_ref
  branch_ref
  workspace_path
  materialization_state
}

resource_claims[]
resource_lease_refs[]
created_at
updated_at
retention_state
```

Every repository component has its own immutable `base_sha`. There is no fake shared SHA for a cross-repository execution.

The canonical four-repository scope remains:

```text
kelvinka38/universal
kelvinka38/metatron-institution
kelvinka38/metatron-workforce
kelvinka38/bios
```

## 9. Amendment A6 — canonical mirror READ is distinct from MUTATION

The rule is not "every read must create a sandbox".

Canonical read-only mirrors/checkouts may support safe inspection:

```text
canonical mirror
  READ / inspect       allowed where policy permits
  MUTATE               forbidden in normal operation
```

Any repository mutation by any normal actor converges on the execution path:

```text
Human / ChatGPT / Claude / Gemini / Worker
        |
        v
ExecutionAttempt
        |
        v
ExecutionResourceScheduler
        |
        v
ExecutionWorkspaceManager
        |
        v
isolated mutable workspace
```

Break-glass remains explicit, scoped, expiring and audited.

## 10. Canonical authority chain

```text
Objective / Work Graph
        |
        v
AutonomySchedulingService
        |
        v
ExecutionAttempt
        |
        v
ExecutionResourceScheduler
        |
        v
ExecutionResourceManager
        |
        v
ExecutionWorkspaceManager
        |
        v
Governed Capability / ActionFabric
        |
        v
RepositoryIntegrationController
        |
        v
Highway / Release Controller
```

There is no duplicate Execution authority, scheduler, workspace authority, Repository Control Plane, ActionFabric, or release plane.

## 11. Existing vs generalized/new components

```text
EXISTING — KEEP / EXTEND
------------------------------------------------------
WorkerActorRuntime                KEEP
AutonomySchedulingService         KEEP / upstream policy
ExecutionAttemptService           KEEP / extend bindings
ExecutionGate                     KEEP / extend protected-effect checks
ObjectiveWorkspaceService         MIGRATE -> compatibility facade
WorkerExecutionSandboxService     KEEP / bind to attempt workspace
Repository Control Plane          KEEP
GeneralWorkspaceActionCatalog     KEEP / bind to attempt workspace
ActionFabric                      KEEP
Governance                        KEEP
Highway                           KEEP / resource-claim convergence
Observation/Evidence              KEEP

NEW / GENERALIZED
------------------------------------------------------
ExecutionWorkspaceManager
ExecutionWorkspaceBinding
ExecutionRepositoryComponent
ExecutionResourceScheduler
ExecutionResourceManager
ResourceClaim
ResourceLease
ResourceFencingState
ResourceCapacitySnapshot
RepositoryIntegrationController
ConflictGraph
IntegrationQueueEntry
DeploymentExecution / immutable artifact path
```

## 12. Workspace ownership

`ObjectiveWorkspaceService` currently keys mutable workspace by Objective + Worker. That is useful isolation substrate but is insufficient for multiple attempts of the same Objective/Worker and cross-repository execution.

Target key is the canonical execution attempt identity.

`ObjectiveWorkspaceService` becomes a compatibility facade over `ExecutionWorkspaceManager`; it MUST NOT persist as a second parallel workspace implementation.

Canonical workspace owner:

```text
attempt_id
```

Canonical root:

```text
/var/lib/metatron/executions/<safe-attempt-key>/
```

## 13. Repository materialization

`RepositoryWorkspaceMaterializationService` already resolves an immutable GitHub commit SHA and keeps repository credentials outside the sandbox. Preserve that property.

Generalized API concept:

```text
materialize(
  attemptId,
  repository,
  requestedRefOrSha,
  componentName
)
-> repository component binding with exact baseSha
```

Materialization is idempotent for identical `(attempt, repository, base_sha)` and fail-closed for conflicting provenance.

## 14. Resource model

Canonical resource classes include:

```text
COMPUTE
  cpu
  memory
  process
  sandbox

REPOSITORY
  repo:<name>:read
  branch:<repo>:<ref>:write

BUILD
  build:jvm
  build:node
  build:python
  build:docker

EXTERNAL
  github:api:read
  github:api:mutation
  provider:<name>

INTEGRATION
  github:<repo>:main
  schema:<domain>
  artifact:<identity>

ENVIRONMENT
  prod:workforce
  prod:gateway
  staging:<service>
```

Claim modes:

```text
READ_SHARED
WRITE_EXCLUSIVE
LEASE_EXCLUSIVE
CAS_SERIALIZED
CAPACITY
```

A global repository write lock MUST NOT serialize unrelated isolated work.

## 15. ResourceLease contract

Minimum durable ResourceLease:

```text
lease_id
resource_id
attempt_id
owner_actor
mode
resource_fencing_token
state_version
issued_at
expires_at
heartbeat_at
status
```

Rules:

- fencing token monotonically increases per protected resource;
- lease acquisition is atomic/CAS protected;
- renewal requires current attempt + current resource fence;
- expiry makes old owner stale;
- release is idempotent;
- reclaim never requires ordinary Human manual unlock;
- stale executor effects are rejected at the protected effect boundary.

## 16. ExecutionResourceScheduler

Input is only Work/attempts already admitted upstream.

It owns:

```text
infrastructure capacity admission
slot allocation
resource-claim feasibility
node/executor selection
WAITING_RESOURCES
backpressure
resource-class fairness
```

It does not own:

```text
business priority semantics beyond supplied priority
Objective planning
Worker role/capability authority
plan approval
authorization
release authorization
```

Example, not hard-coded:

```text
registered workers       1000
ready work                200
active execution slots     20
JVM build slots              8
GitHub mutation slots        4
merge-main slot/repo         1
prod deployment slot/env     1
```

## 17. Backpressure and fairness

If capacity is exhausted, admitted Work remains durable and waits. It does not spawn a process/container merely because a Worker exists.

Scheduling factors may include supplied priority, deadline, aging, resource locality, capacity, objective quota, worker quota, organization quota and cost.

A single Objective cannot consume all infrastructure capacity unless explicit policy allows it.

## 18. Build isolation

Mutable build outputs are attempt/workspace scoped.

No two active attempts share mutable:

```text
build/
target/
dist/
project-local .gradle/
venv/
mutable node_modules/
temporary compiler outputs
```

Shared caches are permitted only when explicitly safe (content-addressed, package cache, immutable layer cache, or synchronization-safe cache semantics).

Dependency cache != build output.

## 19. Integration Plane

Worker/coding authority does not include main mutation.

```text
ExecutionAttempt work product
 -> PR candidate
 -> base freshness
 -> conflict graph
 -> CI / required acceptance
 -> integration queue
 -> expected-main CAS
 -> merge
 -> canonical main evidence
```

V1 conflict graph is deterministic:

```text
repository/base SHA
changed-path overlap
resource-claim overlap
protected-domain overlap
Git mergeability/conflict
```

AI semantic conflict classification may assist later but is not correctness-critical.

## 20. CAS integration

If A and B start from SHA `ABC` and B merges first to `DEF`, A may not silently integrate against stale `ABC`.

```text
A expected_main = ABC
actual_main      = DEF
-> STALE_BASE
```

Policy may rebase/update/re-run CI or return `CONFLICTED`; it may never overwrite silently.

## 21. Idempotency

Consequential effects use attempt-scoped idempotency identity, for example:

```text
attempt_id + action_ref + logical_action_sequence
```

Retries MUST NOT duplicate remote branch, PR, artifact, merge, deployment, state transition or resource acquisition.

## 22. Recovery

`ExecutionAttemptService` remains the attempt-liveness authority.

Resource recovery is layered around it:

```text
attempt heartbeat expires / attempt fenced
 -> Resource Manager observes stale attempt ownership
 -> resource lease expires/reconciles
 -> stale resource fence rejected
 -> workspace retained or reclaimed according to recovery policy
```

Completion is never inferred solely because a process disappeared.

## 23. Deployment isolation

Current `deploy/deploy-production-sha.sh` explicitly checks `requested SHA == current local HEAD`. That guard is safe for the current single-checkout world but is transitional architecture.

Target:

```text
merged immutable SHA
 -> Highway release authorization
 -> DeploymentExecution
 -> immutable prebuilt artifact OR isolated exact-SHA materialization
 -> acquire prod:<service> ResourceLease
 -> deploy exact identity
 -> verify exact SHA / artifact digest
 -> release resource
```

Production deployment MUST be independent of unrelated local branch/HEAD state.

## 24. Highway convergence

Highway remains Release Controller and deploy authority.

The new control plane must not steal its release semantics. Instead, Highway's release tasks submit/adapt their resource claims through `ExecutionResourceManager` and receive resource lease/fencing references for protected release effects.

The migration includes dual-observation acceptance before old Highway-local locking semantics may be retired.

## 25. Operator / AI convergence

Read-only inspection may use canonical mirrors.

Mutation never bypasses isolation:

```text
ChatGPT / Claude / Gemini / Human / Worker
 -> governed ingress
 -> ExecutionAttempt
 -> ExecutionResourceScheduler
 -> isolated ExecutionWorkspace
 -> governed Actions
```

No normal AI maintenance thread may mutate `/opt/metatron/metatron-workforce` directly after migration.

## 26. Break-glass

Emergency direct mutation requires explicit break-glass authorization with actor, reason, scope, expected identity, start, expiry and audit evidence. Break-glass is not the normal development path and does not weaken acceptance requirements.

## 27. Observability

Control Room/Operations must expose at least:

```text
registered Workers
active Worker actors
admitted ExecutionAttempts
WAITING_RESOURCES / RUNNING attempts
capacity by resource class
workspace bindings
resource claims
active/expiring/stale ResourceLeases
resource fencing generations
conflicts / stale bases
integration queue
release/deploy resource ownership
workspace retention/GC
```

## 28. Canonical implementation phases

```text
P0  contract / SoT lock + current substrate audit
P1  ExecutionAttempt workspace/resource binding contracts
P2  ExecutionWorkspaceManager + multi-repository layout
P3  ObjectiveWorkspaceService compatibility facade + sandbox/action migration
P4  ExecutionResourceManager + ResourceClaim/Lease/Fencing
P5  ExecutionResourceScheduler below AutonomySchedulingService
P6  operator/direct coding mutation migration
P7  RepositoryIntegrationController + ConflictGraph + CAS merge queue
P8  build/cache isolation hardening
P9  Highway resource convergence + DeploymentExecution / immutable artifact path
P10 recovery / GC / observability
P11 staged concurrency + scale acceptance
P12 CI, merge, exact production deploy and final ratification
```

Detailed contracts are in:

- `docs/ARCHITECTURE/ELASTIC_EXECUTION_WORKSPACE_RESOURCE_ISOLATION_DETAILED_SPEC.md`
- `docs/ARCHITECTURE/ELASTIC_EXECUTION_WORKSPACE_RESOURCE_ISOLATION_EXECUTION_PLAN.md`

## 29. Mandatory acceptance matrix

```text
EE-01  same-repo concurrent attempts have different mutable workspace paths
EE-02  branch state of A cannot change B
EE-03  clean/build in A cannot delete/change B build artifacts
EE-04  non-conflicting attempts can run concurrently
EE-05  protected conflicting resource claims serialize/fail closed
EE-06  waiting for infrastructure capacity preserves Worker/Objective/Attempt state
EE-07  attempt fencing still rejects stale ExecutionAttempt
EE-08  resource fencing rejects stale ResourceLease owner
EE-09  attempt fence and resource fence are independently validated
EE-10  lease expiry/reconciliation reclaims protected resource
EE-11  stale base SHA cannot silently merge
EE-12  merge queue/CAS serializes protected main mutation
EE-13  consequential retry is idempotent
EE-14  executor crash does not strand resource ownership
EE-15  canonical mirror may be read safely without mutable checkout allocation
EE-16  normal canonical-mirror mutation is denied
EE-17  four canonical repositories remain enforced
EE-18  one attempt can materialize multiple repos with distinct immutable base SHAs
EE-19  repository credential remains outside sandbox/workspace
EE-20  concurrent builds share no mutable output directory
EE-21  AutonomySchedulingService remains upstream WHAT scheduler
EE-22  ExecutionResourceScheduler provides WHEN/WHERE capacity without duplicate work scheduling
EE-23  Highway release authority remains intact while resource claims converge
EE-24  deployment does not depend on unrelated local checkout HEAD
EE-25  only one active prod lease exists per protected environment unless explicit release strategy says otherwise
EE-26  exact deployed SHA/artifact is verified
EE-27  1000 durable Worker actors do not imply 1000 resident processes
EE-28  scheduler obeys capacity/backpressure/fairness
EE-29  GC never deletes active/recoverable workspace
EE-30  every protected mutation preserves attempt/resource attribution and evidence
EE-31  no second Execution/Workspace/Scheduler/Repository/Release authority is introduced
```

## 30. Scale acceptance

Separate proofs:

```text
ACTOR SCALE
  thousands of durable actors/state/mailboxes

QUEUE SCALE
  thousands of ready/waiting Work items with bounded dispatch

EXECUTION CONCURRENCY
  N real concurrent isolated attempts performing repository/build/process work

INTEGRATION
  parallel feature work + controlled main integration

RELEASE
  governed immutable deployment with protected environment ownership
```

A test that merely spawns many sleeping processes is not acceptance.

## 31. Non-goals

This program does not require Kubernetes immediately, one VM/container per Worker, 1000 simultaneous builds, global consensus for every object, or a rewrite of Workforce/ActionFabric/Repository Control Plane/Highway.

V1 may run on one host. The architecture must not assume one shared mutable checkout and must remain scale-out compatible.

## 32. Definition of Done

This closure is complete only when EE-01..EE-31 pass, full build/CI passes, accepted changes are merged through governed source control, and production is verified on the exact accepted immutable SHA/artifact.

Most important incident-regression proof:

```text
2 simultaneous coding/build attempts
same repository
-> independent mutable workspace
-> independent branch/HEAD
-> independent build outputs
-> zero cross-attempt interference
```

Final verdict only after production evidence:

```text
ELASTIC_EXECUTION_WORKSPACE_RESOURCE_ISOLATION = PASS
ELASTIC_EXECUTION_WORKSPACE_RESOURCE_ISOLATION = PARTIAL
ELASTIC_EXECUTION_WORKSPACE_RESOURCE_ISOLATION = FAIL
```

Until then, Founder approval authorizes implementation of this contract; it does not itself constitute runtime acceptance.
