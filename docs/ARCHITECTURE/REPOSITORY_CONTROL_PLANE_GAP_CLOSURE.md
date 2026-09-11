# Repository Control Plane Gap Closure

Status: IMPLEMENTATION + ACCEPTANCE CONTRACT — 2026-09-11

## Objective
Close the architectural gap that allows Worker or planner cognition to treat GitHub authentication/connection as Worker work. Repository access is an institutional Execution dependency, not a Human/Worker task and not a model/provider-specific lane.

This closure is derived from `AGENTS.md`, `docs/SOT_ENFORCEMENT_IMPLEMENTATION_DETAIL_CLOSURE.md`, `docs/SOT_ENFORCEMENT_EXECUTION_PLAN.md`, `docs/ARCHITECTURE/CODING_CAPABILITY_GAP_CLOSURE.md`, and the accepted Worker autonomy/runtime closures. It MUST reuse the existing Execution/ActionFabric/CognitiveWorkerRuntime substrate and MUST NOT create another coding, GitHub, MCP, Claude, ChatGPT, Gemini, or Worker execution stack.

## Canonical chain

```text
Human / Worker / ChatGPT / Claude / Gemini / channel ingress
                      |
                      v
               Workforce Objective
                      |
                      v
          approved governed Work plan
                      |
                      v
      Coding Capability / Execution
                      |
                      v
          Repository Control Plane
       - canonical repository scope
       - institutional credential
       - materialization/read
       - local Git work product
       - governed PR publication
                      |
                      v
             Observation/Evidence
```

Ingress identity may differ. Repository authority and credentials do not.

## Ownership invariants

```text
WORKER != GITHUB ACCOUNT
MODEL/CLIENT != REPOSITORY CREDENTIAL OWNER
MCP != REPOSITORY AUTHORITY
OAUTH CLIENT AUTH != GITHUB AUTH
PLANNER != CREDENTIAL PROVISIONER
GITHUB CONNECT/LOGIN != WORK STEP
REPOSITORY CREDENTIAL != SANDBOX SECRET
CODING CAPABILITY != RELEASE AUTHORITY
```

A Worker may request `workspace.repository.materialize`, inspect/edit/test an Objective workspace, create local Git commits, and request `workspace.github.pr.publish`. It MUST NOT receive a GitHub token, perform an interactive account-connect stage, or invent a `connect GitHub` task.

## Audit findings

### R1 — SoT drift in previous Coding Gap Closure
`CODING_CAPABILITY_GAP_CLOSURE.md` correctly states that Coding is one capability over governed Execution, but its historical G3 text says direct MCP coding executes outside the Workforce cognitive/runtime process. Current SOT Enforcement requires consequential internal/external ingress to converge on the same institutional execution services. The historical direct-lane wording is therefore superseded for repository coding authority.

### R2 — Repository credentials are infrastructure, not Work
Current Workforce repository materialization and PR publication receive `GITHUB_TOKEN` at runtime and deliberately keep it outside the Worker sandbox. This ownership direction is correct. The gap is that credential readiness/lifecycle is not represented as one canonical Repository Control Plane contract.

### R3 — Production health can be weaker than repository capability readiness
A healthy Workforce process is not sufficient evidence that repository materialization/publication is usable. Repository-capable production must have a secret-safe readiness/preflight contract for its institutional repository credential and canonical repository scope.

### R4 — Planner may manufacture infrastructure stages
Frontier planning is allowed to emit `UNAVAILABLE:<need>` steps. Without an explicit conformance rule it may turn a repository infrastructure dependency into Work such as GitHub connect/login/auth/setup. That violates ownership: planner output is a Work proposal, not infrastructure provisioning.

### R5 — Wrong failure semantics can cause cognitive retry loops
`CognitiveWorkerRuntime` converts generic runtime failures into Action observations and lets the Brain reflect/retry. A repository-control-plane authentication/provisioning failure is not a code-repair problem. It must block the affected Work scope as an institutional dependency failure; it must not trigger creative `connect GitHub` planning or blind retries.

### R6 — Missing integrated Worker repository E2E proof
Existing coding acceptance proves the coding loop and mocked/governed PR requirement, but closure requires an integrated proof that a real Worker can execute repository work through institutional credentials without a Human account-connect stage.

## Gap closure requirements

### G1 — Canonical Repository Control Plane ownership
Execution owns repository effects and credential resolution. Worker cognition and external AI clients consume repository actions but never credentials.

Required repository actions remain the shared surface:

```text
workspace.repository.materialize
workspace.file.list/search/read/patch/write
workspace.dependencies.install
workspace.process.run / workspace.shell.run
workspace.build.run / workspace.test.run
workspace.git.status / workspace.git.diff / workspace.git.run
workspace.github.pr.publish
```

No `github.connect`, `github.login`, `github.oauth`, `github.token`, or equivalent Worker action is permitted.

### G2 — Canonical repository scope
Repository Control Plane access for the institutional MCP/direct repository lane is explicitly limited to the canonical repositories currently approved for this scope:

```text
kelvinka38/universal
kelvinka38/metatron-institution
kelvinka38/metatron-workforce
kelvinka38/bios
```

Operational host workspaces are not aliases for these repositories. Owner-wide `kelvinka38/*` authorization is not sufficient.

### G3 — Planner conformance
Execution planning MUST reject or normalize any proposed Work step whose semantic purpose is repository credential provisioning, GitHub login/connect/auth/token setup, or equivalent infrastructure ownership transfer.

If repository capability is unavailable, the typed condition is:

```text
REPOSITORY_CONTROL_PLANE_UNAVAILABLE
```

It is a blocked institutional dependency, not a Worker task. The planner may not satisfy it by inventing a Human/Worker login step.

### G4 — Repository readiness
Production composition must expose a secret-safe readiness predicate for repository capability. At minimum:

```text
credential configured
canonical repository scope configured
materialization adapter available
proposal publisher available
credential not exposed to sandbox
```

No secret value or hash is emitted as normal evidence.

### G5 — Failure semantics
Repository provisioning/authentication failures are typed and fail closed. They are not fed back to frontier cognition as a generic code/tool failure for repeated creative recovery.

Required behavior:

```text
repository auth/provisioning unavailable
-> affected dispatch FAILED/BLOCKED with typed reason
-> no Worker-created connect/login step
-> no identical blind retry
-> management may resume after institutional dependency recovers
```

### G6 — Ingress convergence
Claude OAuth, ChatGPT trusted ingress, Gemini credentials, Web/Telegram/Zalo, and future channels only authenticate/admit the caller. They do not own GitHub credentials. Consequential repository requests converge on the same Workforce Repository Control Plane.

### G7 — Worker E2E acceptance
Acceptance must prove a canonical Worker can:

```text
Objective
-> repository materialize
-> inspect
-> mutate bounded source
-> build/test
-> diagnose/change/retry when appropriate
-> local commit
-> governed unmerged PR publication
-> evidence-backed completion candidate
```

and simultaneously prove:

```text
no interactive GitHub connect/login Work step
no repository credential in Worker sandbox
no merge authority
no deploy authority
unknown repository outside canonical allowlist rejected
```

## Required implementation program

### P0 — Lock this contract and mark conflicting historical direct-lane wording superseded
No new parallel lane.

### P1 — Add planner anti-infrastructure-stage conformance
Reject `connect/login/auth/token GitHub` as Work and return the typed Repository Control Plane blocker when institutional capability is unavailable.

### P2 — Add Repository Control Plane readiness contract
Centralize repository capability readiness/scope behind Execution-owned service/configuration instead of client/model logic.

### P3 — Add typed repository dependency failure
Materialization/publication auth/provisioning failures terminate the cognitive retry path for that dispatch and surface a deterministic blocker to Management.

### P4 — Enforce exact four-repository scope
Apply at Workforce repository materialization/direct ingress/MCP repository adapter and acceptance tests.

### P5 — Add integrated Worker E2E proof
Use a bounded fixture or reviewable disposable branch/PR; never merge or deploy from the acceptance Worker.

### P6 — CI + production ratification
Full build, anti-bypass tests, exact immutable SHA deploy, production identity verification, and live Worker repository proof.

## Acceptance matrix

```text
RC-01 planner cannot create GitHub connect/login/auth/token step
RC-02 Worker runtime profile contains repository actions and no credential/connect action
RC-03 repository credential remains outside sandbox
RC-04 missing/invalid repository credential becomes typed institutional blocker, not cognitive retry loop
RC-05 all four canonical repositories are accepted by scope policy
RC-06 same-owner unknown repository is rejected
RC-07 Worker materializes canonical repository through Repository Control Plane
RC-08 Worker can produce tested local commit
RC-09 Worker can request/publish reviewable unmerged PR through Execution-owned publisher
RC-10 no merge/release authority is acquired
RC-11 external AI ingress and Worker ingress use same repository authority substrate
RC-12 exact deployed SHA passes production proof
```

## Definition of done
This gap is not complete until RC-01..RC-12 pass and production is verified on one exact immutable SHA. A successful Claude OAuth session, a working MCP tool call, a configured token, or a mocked PR alone does not close this gap.

Final verdict must be one of:

```text
REPOSITORY_CONTROL_PLANE_CLOSURE = PASS
REPOSITORY_CONTROL_PLANE_CLOSURE = PARTIAL
REPOSITORY_CONTROL_PLANE_CLOSURE = FAIL
```
