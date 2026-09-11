# Metatron Coding Capability — Gap Closure

**Status: ACCEPTED CODING BASELINE + REPOSITORY CONTROL PLANE FOLLOW-UP — 2026-09-11**

## Decision

Metatron does not need a second Codex architecture. Coding is a capability over the existing governed Execution substrate.

Canonical ownership:

```text
Worker ingress ───────┐
External AI ingress ──┼──> Workforce / governed Coding Capability
Other channels ───────┘              |
                                     v
                              Execution substrate
                                     |
                    ┌────────────────┼────────────────┐
                    v                v                v
             Repository Control   Sandbox        Build/Test
                 Plane               |                |
                    └────────────── Git/PR ───────────┘
                                     |
                                     v
                           Release handoff only
```

Cognition selects/proposes actions. Execution owns effects. MCP is transport/adaptation ingress and never becomes a second institutional Execution authority. Client authentication authenticates the caller; it does not become repository authority or a repository credential.

## Audited Worker coding substrate

`execution.general.workspace` plus `GeneralCognitiveWorkerBrain`, `GeneralWorkspaceActionCatalog`, Objective workspaces and the sandbox provide:

1. repository materialization;
2. file list/search/read;
3. bounded patch/write;
4. allow-listed process/shell execution;
5. dependency installation;
6. build/test;
7. failure observation and diagnose/change/retry;
8. Git status/diff/add/commit;
9. governed GitHub PR publication.

The Worker brain requires inspection before editing unfamiliar code and rejects blind repetition after failed build/test/tool observations.

## Release ownership

Production deployment is intentionally **not** self-granted by `runtime-profile:general-engineering-worker:v1`.

Coding Capability owns code-to-reviewable-artifact closure. Release/Highway owns authorized production rollout and post-deploy verification. A coding Worker may produce a tested committed reviewable proposal but does not silently acquire merge/release authority.

## Accepted baseline and reopened repository-control-plane gap

### G1 — Worker coding acceptance proof — ACCEPTED

`CodingCapabilityGapClosureAcceptanceTest` machine-checks the general engineering coding primitive set and the bounded loop:

```text
materialize
-> inspect/search/read
-> patch
-> failed test
-> diagnose/change
-> successful test
-> git add
-> git commit
-> governed PR publication
-> evidence-backed completion candidate
```

### G2 — Deploy/verification ownership — ACCEPTED BY BOUNDARY

Deploy remains outside the general coding profile. This is a governance boundary, not missing coding capability.

### G3 — External/direct coding ingress — SUPERSEDED OWNERSHIP WORDING

Historical text in this document stated that MCP direct coding could execute through a host/broker control plane independently of the Workforce process. That description is no longer authoritative for repository coding effects.

Current SOT Enforcement requires consequential internal and external ingress to converge on the same institutional execution/governance services. The canonical rule is:

```text
ChatGPT / Claude / Gemini / MCP / channel
                |
                v
        authenticated/admitted ingress
                |
                v
      Workforce Coding Capability
                |
                v
       Repository Control Plane
                |
                v
        governed Execution effects
```

Operational host compatibility tools may exist for administration/recovery, but they are not a second canonical repository/coding authority and do not define Worker architecture.

### G4 — Shared ownership contract — ACCEPTED, WITH FOLLOW-UP

Worker coding and external AI coding must share repository/effect ownership even when ingress differs. The follow-up contract is `docs/ARCHITECTURE/REPOSITORY_CONTROL_PLANE_GAP_CLOSURE.md`.

That closure specifically addresses observed production behavior where planning/cognition could incorrectly turn GitHub authentication/connection into Worker Work.

## New non-negotiable repository rule

```text
WORKER != GITHUB ACCOUNT
MODEL/CLIENT != REPOSITORY CREDENTIAL OWNER
GITHUB CONNECT/LOGIN/AUTH/TOKEN != WORK STEP
OAUTH CLIENT AUTH != GITHUB AUTH
```

A Worker consumes governed repository actions. Repository credentials stay inside institutional Execution/Repository Control Plane infrastructure. If that dependency is unavailable, the affected Work is blocked as an institutional dependency; the planner/Worker must not manufacture a `connect GitHub` stage.

`RepositoryControlPlaneWorkContractTest` and the `ExecutionWorkSpec` contract now fail closed if a planner attempts to encode GitHub/repository credential provisioning as Work.

## Security / governance invariants

- no second Cognitive Runtime;
- no second Action/Execution Fabric;
- no model-specific Codex clone;
- MCP/client authentication is ingress identity only;
- Worker sandbox never owns repository credentials;
- no GitHub connect/login/auth/token Work stage;
- coding profile does not self-grant release authority;
- anonymous/spoofed MCP execution remains denied;
- repository publication remains governed and proposal-only;
- exact-SHA production release/verification remains a separate explicit authority.

## Current closure state

The original coding primitive baseline remains accepted. Repository-control-plane correctness is **not** claimed complete until the dedicated follow-up closes its RC-01..RC-12 acceptance matrix and production proof on one exact immutable SHA.
