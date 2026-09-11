# Metatron Coding Capability — Gap Closure

Status: IMPLEMENTATION BASELINE / ACCEPTANCE CONTRACT

## Decision
Metatron does not need a second Codex architecture. The current Workforce already owns a governed engineering loop through `execution.general.workspace`, `GeneralCognitiveWorkerBrain`, `GeneralWorkspaceActionCatalog`, Objective workspaces, sandbox execution, local Git, and governed GitHub proposal publication.

Coding Capability is therefore a gap-closure program over the shared Execution substrate, not a new cognition or execution stack.

## Audited current capability
The current Worker action contract already supports:

1. repository materialization;
2. file list/search/read;
3. bounded patch/write;
4. allow-listed process/shell execution;
5. dependency installation;
6. build and test;
7. failure observation and cognition-driven diagnose/change/retry;
8. Git status/diff/add/commit;
9. governed GitHub PR publication.

The Worker brain explicitly requires inspection before editing unfamiliar code and rejects blind retry after failed build/test/tool observations.

A separate institutional adapter exists for CI and production-deploy workflow dispatch, but it is not currently composed into the general workspace coding action catalog/brain. Therefore deploy/production verification is not yet proven as part of the autonomous coding loop.

## Gap matrix

### G1 — Worker coding acceptance proof
Implementation primitives exist, but no single acceptance contract proves an unfamiliar engineering Objective can autonomously traverse inspect -> diagnose -> patch -> test/build -> recover -> diff -> commit/PR and evidence-backed COMPLETE.

Closure: add end-to-end acceptance coverage against the existing shared substrate. Completion must fail closed when mandatory engineering evidence is absent.

### G2 — Deploy and production verification composition
Production deploy primitives exist outside the general workspace coding catalog. The coding loop therefore cannot yet claim autonomous code-to-production closure.

Closure: compose governed deployment and post-deploy verification through Execution authority. Deployment remains consequence-sensitive and must never be implicitly granted by a coding role/profile.

### G3 — Direct Coding Lane
External agents currently access bounded MCP/server primitives, but there is no canonical transport-neutral Coding Capability contract shared by ChatGPT, Claude, Gemini and Workforce.

Closure: expose the same Execution coding substrate through a direct ingress that does not transit the Workforce runtime. Workforce outage must not disable direct coding. Direct ingress may authenticate an external agent, but must not bypass repository, sandbox, authorization, consequence, commit, deploy or verification policy.

### G4 — Shared ownership contract
Worker coding and direct coding must not evolve into separate execution universes.

Closure invariant:

`Worker ingress -> Coding Capability -> Execution substrate`

`Direct agent ingress -> Coding Capability -> Execution substrate`

Cognition may select actions; Execution owns effects. MCP is transport/adaptation, not institutional action authority.

## Acceptance target A — Workforce Coding Worker E2E
Given an authorized unfamiliar repository engineering Objective, the Worker must autonomously:

`materialize -> inspect/search/read -> diagnose -> patch/write -> test/build -> inspect failure if any -> state-changing repair -> retry -> diff/status -> commit -> PR/publish when requested -> deploy when explicitly authorized/requested -> verify runtime -> COMPLETE`

Acceptance requires durable evidence for every required stage. A failed verification cannot be converted to COMPLETE by model assertion. A repeated identical failed action without diagnostic/state change is rejected.

## Acceptance target B — Direct Coding E2E
An authenticated ChatGPT, Claude or Gemini client must be able to execute the same governed coding lifecycle through the shared Coding Capability without a live Workforce process.

Acceptance requires:

- no dependency on Worker scheduling, assignment, cognition loop or Workforce conversational runtime;
- identical workspace/repository boundaries and mutation guards;
- identical build/test and Git evidence semantics;
- identical deploy authorization and production verification semantics;
- no raw production shell as the canonical coding interface;
- outage test proving Direct Coding remains usable when Workforce is unavailable.

## Non-goals

- no second Cognitive Runtime;
- no second Action/Execution Fabric;
- no model-specific Codex clone;
- no direct-agent bypass of institutional governance;
- no automatic production deployment merely because code tests pass.

## Execution sequence

C0. Freeze this ownership and acceptance contract.

C1. Add Worker Coding E2E acceptance around existing primitives; close only observed gaps.

C2. Compose governed deploy + post-deploy verification into Coding Capability under explicit authority.

C3. Define a transport-neutral Coding Session/Request/Observation contract over the shared Execution substrate.

C4. Add Direct Coding ingress for MCP clients without routing through Workforce.

C5. Acceptance A: real Worker engineering Objective with recovery path and evidence-backed completion.

C6. Acceptance B: direct client performs equivalent bounded engineering work while Workforce is unavailable.

C7. Production rollout, exact-SHA verification, and canonical source-governance closure.

## Definition of done
Only after both A and B pass may Metatron claim a Codex-equivalent Coding Capability. Existing coding primitives alone are not sufficient evidence, and Direct Coding alone is not sufficient evidence of Worker autonomy.
