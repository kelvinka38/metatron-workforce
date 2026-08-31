# Workforce Autonomy P10 — Golden Slice 2 Mutation Fixture

Status: PRODUCTION ACCEPTANCE FIXTURE

This file exists only to provide a repeatable, tightly bounded mutation target for Golden Slice 2.

Canonical `main` MUST retain the sentinel below as `UNSET`. The governed `repository.pr.propose` capability may replace the sentinel only on its Objective-scoped `autonomy/gs2-*` proposal branch and may open a Pull Request for Human review. The capability MUST NOT merge that Pull Request and MUST NOT mutate any other repository path.

GS2_AUTONOMOUS_PROBE=UNSET
