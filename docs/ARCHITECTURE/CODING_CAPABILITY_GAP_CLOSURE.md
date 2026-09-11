# Metatron Coding Capability — Unified Repository Lane Gap Closure

**Status: CORRECTED CANONICAL ARCHITECTURE — 2026-09-11**

## Decision

Metatron has one Coding Capability and one Execution authority. Worker ingress and direct AI ingress are different front doors into the same repository-coding substrate; MCP host workspace tools are not the canonical repository coding implementation.

```text
Worker ingress ───────────────┐
                              │
ChatGPT / Claude / Gemini     │
        │                     │
        ▼                     │
Authenticated MCP            │
        │ repository_*        │
        └──────────────┬──────┘
                       ▼
          Workforce Shared Coding Capability
                       │
                       ▼
              Objective Workspace
                       │
          ┌────────────┼─────────────┐
          │            │             │
     file/search   sandbox/build   Git/PR
          │            │             │
          └────────────┴─────────────┘
                       │
                       ▼
                 Action Fabric
                       │
              Governance / Permit
                       │
                       ▼
                    Effects

Release/deploy ───────────────> Highway only
```

The previous statement that the direct coding lane was implemented by `workspace_*`, `workforce_gradle`, host Git helpers and direct deploy tools as one shared coding substrate was incorrect. Those host workspace tools remain useful operational compatibility/admin tools, but they are not the repository abstraction for ChatGPT/Claude/Gemini coding.

## Canonical direct repository surface

Authenticated direct MCP clients use repository sessions backed by the same Workforce Objective workspace and action catalog used by general engineering Workers:

- `repository_open`
- `repository_list`
- `repository_search`
- `repository_read`
- `repository_patch`
- `repository_write`
- `repository_dependencies_install`
- `repository_process`
- `repository_shell`
- `repository_git_status`
- `repository_git_diff`
- `repository_git_run`
- `repository_build`
- `repository_test`
- `repository_pr_publish`

`repository_open` materializes a private repository into an isolated Objective workspace. The returned Objective identity is bound to the verified direct client and repository provenance. Subsequent actions must use the same Objective and repository identity.

## Shared implementation

The direct lane does not reimplement coding primitives. It adapts requests to:

- `GeneralWorkspaceActionCatalog`
- `ObjectiveWorkspaceService`
- `RepositoryWorkspaceMaterializationService`
- `WorkerExecutionSandboxService`
- `GitHubWorkspaceProposalPublisher`
- `ActionFabric`

This means Worker coding and direct AI coding use the same file, process, build/test, Git and PR behavior.

## Governance

Read-only coding actions execute with Worker/Assignment/Authorization attribution through Action Fabric. Mutating actions additionally require the existing SoT governance chain:

```text
Founder standing repository authority
        -> GovernancePlanService
        -> ExecutionAttemptService
        -> GovernanceAttemptBindingService
        -> ExecutionGate / ExecutionPermit
        -> ActionFabric mutation
```

The direct adapter cannot invent a new execution authority or bypass permits. The allowed repository authority is intentionally limited to the Founder repository namespace `kelvinka38/*`; arbitrary external owners are rejected.

## Credential boundary

GitHub credentials remain inside Workforce services that already own private repository materialization and governed proposal publication. MCP does not receive a GitHub token and does not become a second GitHub execution stack.

The direct HTTP adapter is internal-only: it is addressed through the private Docker network, accepts only verified direct clients (`chatgpt`, `claude`, `gemini`), rejects forwarded/proxied requests, and the public Gateway must not route `/internal/metatron/direct-coding/*`.

## Release boundary

Coding ends at a tested, committed, reviewable GitHub proposal. Merge/release/deploy remains consequence-sensitive institutional work owned by Highway. Direct repository tools therefore do not expose a repository-scoped production deploy primitive.

Host operations such as exact-SHA Workforce deploy and production verification may still exist as administrative MCP operations, but they are outside the Coding Capability contract and must not be mapped as repository coding stages.

## Compatibility tools

Legacy/operational tools including `workspace_read_file`, `workspace_write_file`, `workspace_search`, bounded host Git helpers and `workforce_gradle` remain available where required for Metatron server administration. Their allowlisted host workspaces are intentionally narrow. A private repository such as `kelvinka38/bios` must never need to be added to that host allowlist merely so an AI client can code against it.

## Invariants

1. One Coding Capability; no ChatGPT/Claude/Gemini-specific coding stacks.
2. One Action/Execution Fabric for repository effects.
3. Repository work uses isolated Objective workspaces.
4. Direct client identity is bound to the repository session.
5. Repository identity/provenance cannot change inside a session.
6. Mutations require an ExecutionPermit.
7. GitHub credentials remain in Workforce.
8. Repository authority is bounded to the approved owner namespace.
9. Host `workspace_*` tools are operational compatibility only.
10. Coding does not self-grant release authority; Highway owns release.

## Acceptance requirement

Closure is complete only when all of the following are true:

- Workforce full test suite passes.
- MCP registry acceptance asserts `repository_*` as the direct coding contract and rejects mapping stages back to host `workspace_*` tools.
- Anonymous/spoofed MCP execution remains denied.
- Production Workforce runs the commit containing the direct coding adapter.
- Production MCP exposes the repository tool surface.
- A live authenticated direct client can `repository_open` `kelvinka38/bios` and read repository content without adding `bios` to the host workspace allowlist.
- Release/deploy remains outside the repository coding tool set.
