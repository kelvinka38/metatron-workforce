# Metatron Workforce — Coding Agent Instructions

Before modifying Intelligence-related code or documentation, read and follow in this order:

- `/AGENTS.md`
- `/docs/ARCHITECTURE/INTELLIGENCE/README.md`
- `/docs/ARCHITECTURE/INTELLIGENCE/01_FINAL_ARCHITECTURE_PROPOSAL.md`
- `/docs/ARCHITECTURE/INTELLIGENCE/02_DETAILED_ARCHITECTURE.md`
- `/docs/ARCHITECTURE/INTELLIGENCE/03_TRACEABILITY_MATRIX.md`

Upstream canonical SOT/policy always wins.

Key rules:

- Human language understanding, multilingual translation, slang/typo resolution, and semantic normalization use frontier-model capability; do not build a competing general-purpose NLP subsystem.
- Never route raw Human natural language directly into institutional execution through keyword/regex/continuation matching as the principal semantic architecture.
- Use deterministic computation when sufficient after the appropriate semantic normalization/planning boundary.
- Provider unavailability is an explicit failure state, not permission to bypass semantic cognition.
- Worker != LLM/provider session.
- Intelligence != authority.
- Claim != evidence; consensus != correctness.
- Workplace owns meetings; Intelligence may enhance deliberation but must not steal meeting ownership.
- Intelligence Case is a runtime coordination construct and must reference, not absorb, state owned by other canonical domains.
- Acquire reliable information before spending unnecessary reasoning capacity.
- Multi-model reasoning is escalation, not default.
- No execution bypasses Assignment/Authorization/Gateway/Execution boundaries.
- Unverified model output does not automatically become institutional Knowledge.

For extensions not explicitly named by SOT: if they remain within canonical boundaries and create meaningful value, implement the best compliant version rather than weakening the feature merely because it is not a canonical primitive.
