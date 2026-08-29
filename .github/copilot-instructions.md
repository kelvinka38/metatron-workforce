# Metatron Workforce — Coding Agent Instructions

Before modifying Intelligence-related code or documentation, read and follow:

- `/AGENTS.md`
- `/docs/ARCHITECTURE/METATRON_INTELLIGENCE_ARCHITECTURE_FINAL_PROPOSAL.md`
- `/docs/ARCHITECTURE/METATRON_INTELLIGENCE_DETAILED_ARCHITECTURE.md`
- `/docs/ARCHITECTURE/METATRON_INTELLIGENCE_TRACEABILITY_MATRIX.md`

Upstream canonical SOT/policy always wins.

Key rules:

- Human language understanding, multilingual translation, slang/typo resolution, and semantic normalization should use frontier-model capability; do not build a competing general-purpose NLP subsystem.
- Worker != LLM/provider session.
- Intelligence != authority.
- Claim != evidence; consensus != correctness.
- Workplace owns meetings; Intelligence may enhance deliberation but must not steal meeting ownership.
- Intelligence Case is a runtime coordination construct and must reference, not absorb, state owned by other canonical domains.
- Use deterministic computation when sufficient.
- Acquire reliable information before spending unnecessary reasoning capacity.
- Do not use keyword/continuation heuristics as the core semantic architecture.
- Multi-model reasoning is escalation, not default.
- No execution bypasses Assignment/Authorization/Gateway/Execution boundaries.
- Unverified model output does not automatically become institutional Knowledge.

For extensions not explicitly named by SOT: if they remain within canonical boundaries and create meaningful value, implement the best compliant version rather than weakening the feature merely because it is not a canonical primitive.
