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

## Deployment success boundary and storage hygiene

`workforce-deploy` owns only the production mutation plus structural verification needed to prove the exact immutable revision is healthy locally: exact image/SHA identity, Workforce health, isolated sandbox health, and credential isolation. Internet egress, public Gateway, Telegram, and broader product acceptance belong to Highway `on_success` acceptance tasks. A transient external check MUST NOT relabel an already-successful production mutation as a failed deployment.

Before building replacement images, the deploy task performs bounded Docker image hygiene. It preserves every image referenced by a container, preserves the explicit `metatron-workforce:rollback` tag, preserves three recent additional images per Workforce repository, removes at most forty older tags per deployment, and prunes only dangling images. Volumes are never pruned by this path. This keeps immutable release semantics while preventing historical deployment images from exhausting the host filesystem.
