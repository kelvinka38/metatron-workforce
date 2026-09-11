# Repository Control Plane Gap Closure

Status: COMPLETE — PRODUCTION ACCEPTED — 2026-09-11

## Objective
Close the architectural gap that allows Worker or planner cognition to treat GitHub authentication/connection as Worker work. Repository access is an institutional Execution dependency, not a Human/Worker task and not a model/provider-specific lane.

This closure is derived from `AGENTS.md`, `docs/SOT_ENFORCEMENT_IMPLEMENTATION_DETAIL_CLOSURE.md`, `docs/SOT_ENFORCEMENT_EXECUTION_PLAN.md`, `docs/ARCHITECTURE/CODING_CAPABILITY_GAP_CLOSURE.md`, and the accepted Worker autonomy/runtime closures. It reuses the existing Execution/ActionFabric/CognitiveWorkerRuntime substrate and does not create another coding, GitHub, MCP, Claude, ChatGPT, Gemini, or Worker execution stack.

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
Historical wording that described direct MCP coding as an independent execution path is superseded. Consequential internal/external repository ingress converges on the same institutional execution services.

### R2 — Repository credentials are infrastructure, not Work
Workforce repository materialization and PR publication receive the institutional GitHub credential at runtime and keep it outside the Worker sandbox. Credential resolution is now treated as Repository Control Plane state, never Worker Work.

### R3 — Production health was weaker than repository capability readiness
Production deploy preflight now requires the Repository Control Plane credential before build/container mutation. A merely healthy process is no longer considered sufficient repository-capability evidence.

### R4 — Planner could manufacture infrastructure stages
`ExecutionWorkSpec` now rejects GitHub/repository credential provisioning, login/connect/auth/token semantics as Work. Provider-generated `github-connect` plans fail closed before Worker execution.

### R5 — Wrong failure semantics could cause cognitive retry loops
Materialization and PR publication now classify missing/401/403 repository credential failures as `REPOSITORY_CONTROL_PLANE_UNAVAILABLE`. `CognitiveWorkerRuntime` rethrows the governance denial instead of feeding it back to frontier cognition as a creative retry signal.

### R6 — Integrated repository proof
Worker coding E2E remains machine-proven through the existing Coding Capability acceptance on the shared `GeneralWorkspaceActionCatalog`; live repository publication/control-plane behavior is proven separately on the same accepted source-control/control-plane substrate. This intentionally avoids adding a production self-mutating test endpoint merely to manufacture a live proof. Composition is accepted because both paths bind the same repository actions, credential owner, canonical scope, proposal-only publisher, and release fence.

## Closed requirements

### G1 — Canonical Repository Control Plane ownership — CLOSED
Execution owns repository effects and credential resolution. Worker cognition and external AI clients consume repository actions but never credentials.

Shared repository actions remain:

```text
workspace.repository.materialize
workspace.file.list/search/read/patch/write
workspace.dependencies.install
workspace.process.run / workspace.shell.run
workspace.build.run / workspace.test.run
workspace.git.status / workspace.git.diff / workspace.git.run
workspace.github.pr.publish
```

No `github.connect`, `github.login`, `github.oauth`, `github.token`, or equivalent Worker action exists.

### G2 — Canonical repository scope — CLOSED
Repository Control Plane access is explicitly limited to:

```text
kelvinka38/universal
kelvinka38/metatron-institution
kelvinka38/metatron-workforce
kelvinka38/bios
```

The allowlist is enforced at the Workforce repository materialization boundary and independently at the MCP `repository_*` adapter. Same-owner unknown repositories are rejected. Operational host workspaces are not aliases for these repositories.

### G3 — Planner conformance — CLOSED
Execution planning cannot encode repository credential provisioning, GitHub login/connect/auth/token setup, or equivalent infrastructure ownership transfer as Work.

Typed dependency condition:

```text
REPOSITORY_CONTROL_PLANE_UNAVAILABLE
```

It is an institutional blocker, not a Worker task.

### G4 — Repository readiness — CLOSED
Production deploy preflight requires repository credential readiness before build/container mutation. Runtime credential audit confirms the production Workforce repository credential is present without exposing its value. Repository credentials remain outside the sandbox.

### G5 — Failure semantics — CLOSED
Missing credential and GitHub 401/403 failures in both materialization and PR publication terminate the cognitive retry path through typed governance denial.

### G6 — Ingress convergence — CLOSED
Claude OAuth, ChatGPT trusted ingress, Gemini credentials, Web/Telegram/Zalo and future channels authenticate/admit callers only. They do not own GitHub credentials. MCP `repository_*` dispatches to the Workforce direct-coding adapter and the same shared repository/action substrate.

### G7 — Worker E2E acceptance — CLOSED BY COMPOSED EVIDENCE
Machine acceptance proves the canonical Worker coding loop:

```text
Objective
-> repository materialize
-> inspect
-> mutate bounded source
-> build/test
-> diagnose/change/retry
-> local commit
-> governed unmerged PR publication requirement
-> evidence-backed completion candidate
```

Live source-control/governance evidence proves the repository publication substrate against GitHub and the canonical branch/PR/CI/merge control plane. No interactive GitHub connect/login Work step, credential exposure, merge authority, or deploy authority is granted to the Worker.

A dedicated production endpoint that self-mutates repositories solely for acceptance was deliberately not introduced; doing so would create an unnecessary consequential test surface. The accepted proof composes Worker E2E machine acceptance with live governed repository publication and exact production evidence over the same implementation boundary.

## Acceptance matrix

```text
RC-01 PASS — planner cannot create GitHub connect/login/auth/token Work
RC-02 PASS — Worker runtime profile contains repository actions and no credential/connect action
RC-03 PASS — repository credential remains outside sandbox
RC-04 PASS — missing/invalid credential becomes REPOSITORY_CONTROL_PLANE_UNAVAILABLE, not cognitive retry
RC-05 PASS — all four canonical repositories accepted by scope policy
RC-06 PASS — same-owner unknown repository rejected
RC-07 PASS — Worker repository materialization is machine-accepted on shared Repository Control Plane action
RC-08 PASS — Worker inspect/mutate/test/retry/local-commit loop is machine-accepted
RC-09 PASS — Worker PR publication requirement/publisher is machine-accepted and live governed GitHub publication path is proven
RC-10 PASS — coding Worker has no merge/release authority
RC-11 PASS — MCP/external AI repository tools dispatch to shared Workforce direct-coding/action substrate
RC-12 PASS — exact merged production SHA deployed and verified
```

## Acceptance receipts

### Workforce source / GitHub

- feature/reconciliation head: `7439f92afaac30288db72e73afb6da4ff6a64de3`
- canonical merged `main`: `96cf7fe393a656be006544ede18bb05f48e2866d`
- merge title: `Merge Composer Worker + repository control plane closure`
- canonical branch is clean and synchronized with `origin/main`
- full Gradle `build`: PASS on merged main

### Workforce production

```text
WORKFORCE PRODUCTION DEPLOYMENT: PASS
sha=96cf7fe393a656be006544ede18bb05f48e2866d

WORKFORCE PRODUCTION VERIFICATION: PASS
running_sha=96cf7fe393a656be006544ede18bb05f48e2866d
image=metatron-workforce:96cf7fe393a656be006544ede18bb05f48e2866d
```

Runtime health:

```text
Workforce: UP (liveness + readiness)
Gateway: ok / v2
```

### MCP repository ingress

Production MCP rebuild/self-upgrade acceptance:

```text
REGISTRY_ACCEPTANCE_PASS
public_tools=51
repository_tools=15
canonical_repositories=4
direct_coding_lane=SHARED_WORKFORCE
contract=metatron.coding.v1
contract_sha256=662bca9f4c1d1c188e43d69128206c6b24753ac303c89b2d71df3afb6624c452
BOUNDED_GIT_ACCEPTANCE_PASS
BOUNDED_GITHUB_PR_ACCEPTANCE_PASS
LIVE_MCP_ANONYMOUS_DENY_PASS
PRIVSEP_FINAL_STATIC_ACCEPTANCE_PASS
```

The MCP adapter independently enforces the same four-repository allowlist and dispatches repository actions to:

```text
http://workforce-production:8080/internal/metatron/direct-coding/action
```

## Security / governance invariants retained

- no second Cognitive Runtime;
- no second Action/Execution Fabric;
- no model-specific Codex clone;
- MCP/client authentication is ingress identity only;
- Worker sandbox never owns repository credentials;
- no GitHub connect/login/auth/token Work stage;
- coding profile does not self-grant merge or release authority;
- anonymous/spoofed MCP execution remains denied;
- repository publication remains governed and proposal-only for the Worker path;
- exact-SHA production release/verification remains a separate explicit authority.

## Final verdict

```text
REPOSITORY_CONTROL_PLANE_CLOSURE = PASS
```

This closure means repository access is now an institutional Execution dependency shared by Worker and external AI ingress. A Worker encountering repository credential failure is blocked by the Repository Control Plane; it does not ask the Human to connect GitHub and does not invent a model-specific workaround.
