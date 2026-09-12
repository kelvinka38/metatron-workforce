# Repository Credential Authority Gap Closure

Status: IMPLEMENTED — canonical technical closure.

## Authority

`RepositoryCredentialAuthority` is the single Workforce source for repository authentication.
Workers, model clients, planners, workspaces and sandboxes do not own repository credentials.
The server process resolves `GITHUB_TOKEN` only through this authority for normal Repository
Control Plane consumers. Missing credentials fail closed as `REPOSITORY_CONTROL_PLANE_UNAVAILABLE`.

## Normal path

Human / Worker / ChatGPT / Claude / Gemini -> governed ingress -> Workforce -> Repository Control Plane -> RepositoryCredentialAuthority -> GitHub.

Legacy host `workspace_git_commit_push` remains denied. MCP source-control CAS helpers are an
operator compatibility plane only; they use the canonical host credential backing and may not
become a second Worker coding authority.

## Invariants

- No repository credential in Worker sandbox or Work ontology.
- No `github.connect`, login, OAuth or token Work.
- No model/client-specific credential lane.
- Canonical four-repository allowlist remains enforced.
- Missing credential is infrastructure unavailability, not a planning task.
- Coding authority, merge authority and release authority remain separate.
