# Release Authority Convergence Gap Closure

Status: CANONICAL

Production Workforce mutation has one authority: Highway. Any compatibility deploy entrypoint may validate and publish an immutable exact-SHA release, but it MUST submit `workforce-build` and `workforce-deploy` to Highway and MUST NOT execute `docker compose up`, recreate Workforce containers, or otherwise mutate `prod:workforce` directly.

Canonical chain:

`exact SHA -> immutable release -> Highway workforce-build -> tested artifact -> Highway workforce-deploy -> prod:workforce WRITE -> production`

`highway/publish-release.sh` is intentionally independent of mutable checkout HEAD; it verifies the requested commit exists and archives that exact SHA under the immutable release store.

Invariants:
- one production mutation authority for Workforce: Highway;
- `prod:workforce WRITE` serializes every normal deployment;
- compatibility deploy tooling is an adapter, not a second release plane;
- local branch/HEAD/worktree state cannot select the production revision;
- immutable artifact/source identity remains exact-SHA bound.
