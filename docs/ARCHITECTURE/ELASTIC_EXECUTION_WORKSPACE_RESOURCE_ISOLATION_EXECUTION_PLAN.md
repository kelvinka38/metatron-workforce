# Elastic Execution Workspace & Resource Isolation — Execution Plan

Status: FOUNDER-APPROVED IMPLEMENTATION PLAN — 2026-09-12
Parent contracts:
- `ELASTIC_EXECUTION_WORKSPACE_RESOURCE_ISOLATION_GAP_CLOSURE.md`
- `ELASTIC_EXECUTION_WORKSPACE_RESOURCE_ISOLATION_DETAILED_SPEC.md`

## 1. Execution rules

This plan implements the approved architecture without a parallel Execution authority.

Mandatory rules:

```text
DO NOT create a replacement ExecutionAttempt lifecycle.
DO NOT create a second Work scheduler beside AutonomySchedulingService.
DO NOT create a second Repository Control Plane.
DO NOT create a second ActionFabric.
DO NOT create a second release/deployment authority beside Highway.
DO NOT leave ObjectiveWorkspaceService and ExecutionWorkspaceManager as competing mutable workspace systems.
DO NOT make canonical mirror mutation the normal implementation path.
```

Implementation is P0 -> P12. Build phases may be batched before expensive production acceptance, but contract tests are required at each architecture boundary.

## P0 — Baseline audit and contract lock

### Objective
Capture exact current implementation and prevent duplicate-substrate design.

### Audit
Verify and record:

- `ExecutionAttempt`, `ExecutionAttemptService`, `ExecutionAttemptStore`;
- `ExecutionGate` attempt-fence validation;
- `AutonomySchedulingService` ownership and decisions;
- `ObjectiveWorkspaceService` callers;
- `WorkerExecutionSandboxService` identity/binding;
- `GeneralWorkspaceActionCatalog` workspace assumptions;
- `RepositoryWorkspaceMaterializationService` single-repo assumptions;
- `GitHubWorkspaceProposalPublisher` provenance assumptions;
- `DirectCodingIngressService` attempt/workspace path;
- `RuntimeCapacityCoordinator` current runtime semantics;
- `ManagementLease` and any other fencing stores;
- deployment script current HEAD dependency;
- Highway task/resource registry and current resource claim/lock semantics.

### Deliverables
- audit section/receipt in this program;
- exact Highway-resource mapping document or section;
- no code behavior change except tests/doc entry points if required.

### Gate
No P1 if Highway resource semantics cannot be inspected sufficiently to avoid creating a parallel resource universe.

## P1 — Attempt-bound workspace/resource bindings

### Objective
Bind isolation state to existing `ExecutionAttempt`.

### Add
Suggested package: `com.metatron.workforce.runtime.execution` or existing canonical execution/runtime package after audit.

```text
ExecutionWorkspaceBinding
ExecutionRepositoryComponent
ExecutionWorkspaceBindingStore
FileExecutionWorkspaceBindingStore
ResourceClaim
```

### Extend
`ExecutionAttemptService` only with reference/binding integration needed to validate current attempt ownership; do not add a duplicate state machine.

### Tests
- binding requires existing current attempt;
- stale attempt fencing token cannot create/change binding;
- terminal attempt cannot open a new mutable binding in normal flow;
- multiple attempts of same Worker/Objective receive different workspace IDs.

### Gate
Full tests PASS.

## P2 — ExecutionWorkspaceManager + multi-repository layout

### Objective
Create the one generalized mutable workspace authority.

### Add

```text
ExecutionWorkspaceManager
ExecutionWorkspaceConfiguration
ExecutionWorkspaceMetadataCodec
ExecutionWorkspaceRecoveryPolicy
```

### Root

```text
METATRON_EXECUTION_WORKSPACE_ROOT=/var/lib/metatron/executions
```

### Implement
- allocation by attempt identity;
- containment/symlink protections inherited from ObjectiveWorkspaceService;
- `repos/<component-id>` layout;
- `build/<component-id>` and artifact/evidence directories;
- multi-repository component registration;
- metadata CAS/version;
- seal/retain/dispose lifecycle;
- attempt-fence validation.

### Tests
- same Objective/Worker, two attempts -> distinct roots;
- cross-repo components -> distinct paths/base SHA fields;
- duplicate component/path rejected;
- traversal/symlink tests preserved;
- stale workspace version rejected.

## P3 — ObjectiveWorkspaceService compatibility facade and caller migration

### Objective
Ensure there is one mutable workspace implementation.

### Refactor
`ObjectiveWorkspaceService` delegates to `ExecutionWorkspaceManager` for governed mutating execution.

### Migrate callers

```text
GeneralWorkspaceActionCatalog
WorkerExecutionSandboxService
RepositoryWorkspaceMaterializationService
GitHubWorkspaceProposalPublisher
DirectCodingIngressService / direct coding backend
GeneralWorkspaceAutonomousCapability
```

### Transitional compatibility
A temporary adapter may resolve the current attempt from governed action context. It may not silently recreate old shared Objective+Worker mutable behavior for consequential execution.

### Tests
- old API facade resolves same underlying attempt workspace;
- no second directory allocated for same binding;
- two attempts never map to same mutable path.

### Gate
Search/static acceptance proves no normal mutating path provisions mutable workspace by Objective+Worker alone.

## P4 — Repository materialization generalization

### Objective
Support immutable multi-repository components.

### Refactor
`RepositoryWorkspaceMaterializationService`:

- accept attempt/component binding;
- exact SHA input path plus ref-resolution path;
- write component-scoped provenance;
- idempotent retry for matching provenance;
- reject mismatched provenance;
- preserve `CanonicalRepositoryScope`;
- preserve credential boundary.

### Proposal publisher
Generalize `GitHubWorkspaceProposalPublisher` to select one repository component and bind source/base/head evidence to that component.

### Tests
- all four canonical repos accepted;
- unknown same-owner repo rejected;
- two repos in one attempt materialize separately;
- each retains independent `baseSha`;
- credential never appears in workspace/sandbox payload/evidence.

## P5 — ExecutionResourceManager

### Objective
Create canonical infrastructure/shared-resource ownership below attempt authority.

### Add

```text
ExecutionResourceManager
ResourceClaim
ResourceLease
ResourceFencingState
ResourceStateStore
FileResourceStateStore
ResourceGrant
ResourceAssessment
ResourceCapacitySnapshot
ResourceLeaseReconciler
```

### Implement
- shared read claims;
- exclusive leases;
- capacity claims;
- per-resource monotonic fencing;
- lease TTL/renew/release;
- idempotent acquisition/release;
- CAS on resource state;
- attempt-current validation;
- reconciliation of terminal/fenced attempts.

### Critical test

```text
A gets resource fence 41
A expires
B gets fence 42
A resumes with 41
-> RESOURCE_FENCED
```

### Other tests
- one attempt can hold multiple leases;
- attempt token != resource token semantics;
- no lease grants missing ExecutionAttempt authority;
- read-sharing does not grant write.

## P6 — ExecutionResourceScheduler

### Objective
Add infrastructure WHEN/WHERE scheduling below `AutonomySchedulingService`.

### Add

```text
ExecutionResourceScheduler
ExecutionResourceAdmissionRequest
ExecutionResourceAdmissionDecision
ExecutionQueueStore
ResourceCapacityPolicy
```

### Integrate
`AutonomySchedulingService` output remains the upstream eligibility/worker-allocation input.

Flow:

```text
AutonomySchedulingDecision
 -> begin/bind ExecutionAttempt
 -> derive approved ResourceClaims
 -> ExecutionResourceScheduler
 -> ADMITTED or WAITING_RESOURCES/BLOCKED
```

### Implement
- capacity slots by class;
- durable waiting state;
- per-objective fairness ceiling;
- aging;
- no process spawn before grant;
- executor/locality selection abstraction.

### Tests
- 100 ready attempts with capacity 2 -> at most 2 admitted heavy slots;
- rest durable/waiting;
- no lost attempt identity;
- aging prevents starvation in deterministic fixture;
- upstream scheduling decision cannot be overridden by resource scheduler.

## P7 — Action/sandbox/build isolation + operator mutation convergence

### Objective
All normal repository mutations use attempt-owned workspace.

### Worker path
Bind `GeneralWorkspaceActionCatalog` actions to current attempt workspace/component.

### Sandbox
Add attempt/workspace identity to request/response attribution.

### Operator/direct path
MCP/direct coding mutation creates/uses an ExecutionAttempt and isolated workspace. Read-only canonical mirror operations may remain direct inspection.

### Canonical mirror policy enforcement
Normal mutation tools must not edit/switch/build/commit the shared mirror.

### Build isolation
Ensure mutable outputs reside only under attempt/component directories.

### Incident-regression acceptance — first mandatory concurrency proof
Run two same-repository attempts concurrently:

```text
A branch/change/build/clean
B branch/change/build/clean
```

Assert:

- different filesystem roots;
- different `.git`/branch state;
- A clean does not change B build digest/list;
- B branch switch does not change A HEAD;
- both complete without shared worktree conflict.

Failure blocks P8+ ratification regardless of other tests.

## P8 — RepositoryIntegrationController / ConflictGraph / merge queue

### Objective
Parallel isolated work converges safely into protected refs.

### Add

```text
RepositoryIntegrationController
ConflictGraph
ConflictEdge
IntegrationQueueEntry
IntegrationQueueStore
FileIntegrationQueueStore
```

### Implement
- candidate registration;
- exact expected base SHA;
- changed-path/resource conflict evidence;
- CI evidence requirements;
- protected integration resource claim;
- merge queue;
- expected-main CAS;
- stale-base flow;
- merge result evidence.

### Tests
- A/B same base, B merges first -> A `STALE_BASE`;
- no silent overwrite;
- non-conflicting queue entries serialize protected main mutation while coding remains parallel;
- stale CI/base requires revalidation as policy dictates.

## P9 — Highway resource convergence

### Objective
One canonical resource ownership model without stealing release authority.

### First action
Audit actual Highway task/resource registry and enumerate:

```text
resource IDs
claim modes
ownership scope
lease/lock duration
retry semantics
release semantics
production resources
artifact/source resources
GitHub resources
```

### Add adapter

```text
HighwayResourceClaimAdapter
```

### Migration
- map Highway claims to canonical `ResourceClaim`;
- Highway release tasks acquire canonical ResourceLease;
- validate resource fence before protected release effect;
- dual-observe old + new safety state during transition;
- retire duplicate Highway-local lock state only after equivalence acceptance.

### Tests
- release authority remains Highway-only;
- Resource Manager cannot authorize release by itself;
- same protected prod resource cannot be granted concurrently;
- stale release resource fence is rejected.

## P10 — DeploymentExecution / immutable artifact path

### Objective
Remove architectural dependency on shared local HEAD.

### Transitional implementation
Dedicated deployment materialization of exact merged SHA in isolated release workspace.

### Preferred mature implementation
CI/build produces immutable artifact digest with provenance; Highway deploys digest.

### Add/generalize

```text
DeploymentExecution
ArtifactProvenance
DeploymentWorkspaceProvider (if transitional)
```

### Modify
`deploy/deploy-production-sha.sh` or successor must no longer require unrelated canonical mirror HEAD == requested SHA.

It may still verify:

- requested SHA format;
- approved/merged source identity;
- clean isolated deployment materialization;
- artifact/source provenance;
- production ResourceLease/fence;
- running exact identity.

### Tests
- canonical mirror may stand on unrelated branch/SHA while exact approved release deploy succeeds from isolated materialization/artifact;
- production concurrent deployment attempt is denied/waits;
- running SHA/artifact digest exact verification PASS.

## P11 — Recovery, GC, observability and scale acceptance

### Recovery
Implement/reconcile:

```text
ResourceLeaseReconciler
ExecutionWorkspaceReconciler
IntegrationQueueReconciler
DeploymentReconciler
```

### GC
Only terminal, unleased, unreferenced, retention-expired, CAS-current workspace may be deleted.

### Observability
Expose resource/workspace/scheduler/integration state in Operations/Control Room without secrets.

### Scale tests
Separate:

```text
actor scale: 1000+ durable actors
queue scale: 1000+ waiting/ready items
real execution concurrency: capacity-bounded N
integration serialization: protected refs
release serialization: protected env
```

Do not use sleeping-process count as proof.

## P12 — Full acceptance, GitHub integration, production ratification

### Required pre-merge evidence
- EE-01..EE-31 PASS;
- full Gradle build PASS;
- static no-bypass checks PASS;
- concurrency incident regression PASS;
- Highway convergence acceptance PASS;
- deployment isolation acceptance PASS.

### Source-control flow

```text
clean implementation branch
 -> governed PR
 -> CI PASS
 -> review/acceptance evidence
 -> merge through controlled integration
 -> canonical main exact SHA
```

### Production

```text
accepted merged SHA/artifact
 -> Highway release
 -> protected production resource lease
 -> deploy immutable identity
 -> production health
 -> exact SHA/artifact verification
 -> concurrency smoke acceptance
```

### Final closure
Update the parent Gap Closure with exact receipts and only then set:

```text
ELASTIC_EXECUTION_WORKSPACE_RESOURCE_ISOLATION = PASS
```

## 2. Suggested implementation order by code dependency

Practical dependency ordering:

```text
A. binding models/store
B. workspace manager
C. compatibility facade + repository components
D. resource manager
E. resource scheduler
F. action/sandbox/direct mutation migration
G. concurrency incident regression
H. integration controller
I. Highway adapter
J. deployment isolation
K. recovery/GC/observability
L. scale + production acceptance
```

Do not begin merge/deploy refactor before workspace/resource invariants are machine-proven.

## 3. Change-control triggers

Stop affected scope and raise a new change proposal if implementation requires any of:

- replacing `ExecutionAttempt` as canonical attempt authority;
- replacing `AutonomySchedulingService` as Work scheduler;
- moving repository credential ownership into Worker/sandbox;
- giving `ExecutionResourceManager` release authority;
- bypassing `ExecutionGate` for consequential Actions;
- retaining two independent mutable workspace authorities;
- weakening canonical four-repository scope;
- changing Highway release ownership;
- requiring a distributed database/consensus system that materially changes approved single-host-v1 assumptions.

## 4. Completion discipline

Component completion is not program completion.

Statements such as:

```text
workspace manager works
leases work
scheduler works
MCP works
deploy works
```

are partial evidence only.

The program closes only when the original collision class cannot be reproduced under simultaneous real work and the exact production artifact is verified.
