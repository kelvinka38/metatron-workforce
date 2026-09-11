# Metatron Coding Capability — Gap Closure

**Status: COMPLETE — ACCEPTANCE BASELINE LOCKED — 2026-09-11**

## Decision

Metatron does not need a second Codex architecture. Coding is a capability over the existing governed Execution substrate.

The canonical ownership model is:

```text
Worker ingress ───────┐
                      ├──> Coding Capability / governed execution primitives
Direct AI ingress ────┘             │
                                    ├─ repository/workspace
                                    ├─ sandbox/process
                                    ├─ build/test
                                    ├─ Git/PR
                                    └─ release handoff / verification
```

Cognition selects or proposes actions. Execution owns effects. MCP is a transport/adaptation ingress and never becomes a second institutional Execution authority.

## Audited Worker coding substrate

`execution.general.workspace` plus `GeneralCognitiveWorkerBrain`, `GeneralWorkspaceActionCatalog`, Objective workspaces and the sandbox already provide:

1. repository materialization;
2. file list/search/read;
3. bounded patch/write;
4. allow-listed process/shell execution;
5. dependency installation;
6. build/test;
7. failure observation and diagnose/change/retry;
8. Git status/diff/add/commit;
9. governed GitHub PR publication.

The Worker brain explicitly requires inspection before editing unfamiliar code and rejects blind repetition after failed build/test/tool observations.

## Release ownership clarification

Production deployment is intentionally **not** self-granted by `runtime-profile:general-engineering-worker:v1`.

A separate institutional release adapter and canonical Highway production-deploy ingress exist. A coding Worker may produce a tested committed reviewable proposal, but merge/release authority remains a separate consequence-sensitive institutional capability.

Therefore "Codex-equivalent coding" does not mean a coding role silently acquires production authority. When an explicitly authorized release Objective exists, release/deploy/verification proceeds through the canonical release plane. This avoids creating a second deployment architecture inside the Worker coding brain.

This supersedes the earlier draft assumption that deploy must be embedded directly in the general coding action catalog.

## Gap closure

### G1 — Worker coding acceptance proof — CLOSED

`CodingCapabilityGapClosureAcceptanceTest` machine-checks that the general engineering runtime profile owns the complete coding primitive set and that an unfamiliar mutating engineering flow can traverse:

```text
materialize
-> inspect/search/read
-> patch
-> failed test
-> CONTINUE/recovery
-> diagnostic/state-changing repair
-> successful test
-> git add
-> git commit
-> governed PR publication
-> evidence-backed COMPLETE
```

The acceptance also proves a model-proposed COMPLETE is rejected before required remote proposal evidence exists.

### G2 — Deploy/verification ownership — CLOSED BY BOUNDARY, NOT DUPLICATION

Deploy primitives already exist in the institutional release plane. The coding profile is explicitly tested **not** to contain `github.workflow.production-deploy.dispatch`.

This is the intended governance boundary: Coding Capability owns code-to-reviewable-artifact closure; Release/Highway owns authorized production rollout and post-deploy verification. No second release mechanism is added to the Worker.

### G3 — Direct Coding Lane — CLOSED

The production SSH MCP exposes an independent bounded coding lane with public tools including:

- `workspace_read_file`
- `workspace_write_file`
- `workspace_search`
- `workspace_git_diff`
- `workspace_git_fetch`
- `workspace_git_compare`
- `workspace_git_show`
- `workspace_git_branch`
- `workspace_git_commit_local`
- `workforce_gradle`
- `workforce_deploy_local_sha`
- `workforce_verify_production`
- `production_identity`

These execute through the MCP host/broker control plane rather than the Workforce cognitive/runtime process. ChatGPT/Claude/Gemini therefore do not need a live Worker cognition loop to inspect, edit, test, commit locally, deploy an authorized exact SHA, or verify production.

The legacy `workspace_git_commit_push` path remains non-public; publication must obey official source-control governance.

### G4 — Shared ownership contract — CLOSED

Worker coding and direct coding share the same repository/runtime governance boundary and do not create separate cognitive or execution universes.

```text
Worker: Objective -> Worker cognition -> governed actions -> Execution substrate
Direct: authenticated AI client -> MCP bounded tools -> same governed host/repository/release substrate
```

The ingress differs. Effect authority does not.

## Acceptance receipts

### Workforce

`./gradlew test` after adding `CodingCapabilityGapClosureAcceptanceTest`:

```text
BUILD SUCCESSFUL
```

The acceptance covers recovery after a failed test, state-changing repair before retry, Git closure, PR evidence gating and the release-authority fence.

### Direct MCP

The MCP image acceptance now requires the direct coding tool set and reports:

```text
REGISTRY_ACCEPTANCE_PASS public_tools=36 direct_coding_lane=PASS
BOUNDED_GIT_ACCEPTANCE_PASS
LIVE_MCP_ANONYMOUS_DENY_PASS public_tools=36
PRIVSEP_FINAL_STATIC_ACCEPTANCE_PASS
```

Authentication acceptance remains fail-closed:

```text
REQUEST_GUARD_ACCEPTANCE_PASS verified_client=gemini anonymous=deny spoof=deny
```

The upgraded `metatron-ssh-mcp:next` image passed build-time acceptance and replaced the live MCP container.

## Security / governance invariants

- no second Cognitive Runtime;
- no second Action/Execution Fabric;
- no model-specific Codex clone;
- direct clients do not gain raw root shell as the canonical coding interface;
- coding role/profile does not self-grant release authority;
- anonymous MCP execution remains denied;
- spoofed client identity remains denied;
- source-control publication remains governed by the official GitHub App path;
- exact-SHA production deploy and verification remain separate explicit operations.

## Definition of done

Metatron may now claim a **Codex-equivalent Coding Capability** in the architectural sense:

1. Workforce has a governed coding/recovery loop with machine acceptance; and
2. authenticated external AI clients have an independent direct coding lane over the governed MCP/host substrate.

This claim does **not** imply unrestricted production authority or bypass of GitHub/release governance.
