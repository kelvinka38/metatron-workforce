# METATRON INTELLIGENCE — TRACEABILITY / RECONCILIATION MATRIX

## Status

**FOUNDER APPROVED ARCHITECTURE — DOWNSTREAM DOCUMENT RECONCILIATION RECORD**

This matrix records how the approved Intelligence architecture relates to existing canonical/downstream boundaries. Canonical SOT/policy always wins where exact wording or ownership differs.

## Classification

- `REUSE` — existing canonical/downstream semantic already exists; do not redefine.
- `DERIVE` — architecture derives a product/runtime behavior without creating a new canonical primitive.
- `REFERENCE` — Intelligence may reference external authoritative state but does not own it.
- `EXTEND` — compliant product/runtime capability inside existing boundaries.
- `PROHIBIT` — behavior is explicitly disallowed by existing boundaries.
- `VERIFY-UPSTREAM` — exact canonical naming/ownership must be checked against upstream SOT when direct source access is available.

## Matrix

| Approved requirement | Classification | Existing boundary / source used | Detailed-doc treatment | Ownership effect |
|---|---|---|---|---|
| Frontier models provide multilingual/slang/typo semantic interpretation | EXTEND | Existing provider-neutral Intelligence Fabric; natural-language-first interaction | Human Semantic Interface | None; provider remains capability |
| Do not build competing general-purpose NLP/translation engine | DERIVE | Product architecture + provider capability boundary | Explicit non-goal/invariant | None |
| Worker is not LLM/provider session | REUSE | Workforce / existing Intelligence proposal | Worker integration | No change |
| Intelligence Fabric remains provider-neutral | REUSE | Existing Intelligence architecture/runtime | Fabric contract | No change |
| Deterministic computation before unnecessary LLM usage | DERIVE | Existing information-before-intelligence principle | Computation Planner | No new owner |
| Intelligence Case for stateful bounded analysis | EXTEND / VERIFY-UPSTREAM | No proven conflicting owner in audited downstream docs | Runtime coordination aggregate | Owns only Case-local coordination state |
| Case references Worker | REFERENCE | Workforce owns Worker | worker_ref | Workforce ownership preserved |
| Case references Meeting | REFERENCE | Workplace owns meetings | meeting_ref | Workplace ownership preserved |
| Case references Authorization | REFERENCE | Authorization/Governance/Gateway boundary | authorization_ref | Authority ownership preserved |
| Case references Execution | REFERENCE | Execution domain | execution_ref | Execution ownership preserved |
| Case references Observation/Outcome | REFERENCE | Observation / existing semantic chain | observation_ref / outcome_ref | External ownership preserved |
| Case references Knowledge | REFERENCE | Knowledge admission boundary | knowledge_ref | Knowledge ownership preserved |
| Information Requirements state | EXTEND / VERIFY-UPSTREAM | Existing evidence/retrieval principles | Case-local requirement model | No external ownership transfer |
| Retrieve reliable data before asking Human | DERIVE | Existing information acquisition principle | Acquisition strategy | None |
| Ask Human only when necessary/required | DERIVE | Natural-language-first + authority/access boundaries | Ask-the-Human rule | None |
| Information-value prioritization | EXTEND | No conflicting audited semantic | Planning heuristic, not canonical formula | None |
| Analytical Protocols / Templates | EXTEND / VERIFY-UPSTREAM | BIOS/Universal grammar remains upstream | Reusable analytical procedure | Does not redefine BIOS/Universal |
| FAST / ANALYZE / DEEP depth | EXTEND | Existing progressive intelligence/governance principle | Product depth contract | No semantic ownership change |
| User controls requested depth; Metatron optimizes execution | EXTEND | Resource/capacity routing + product entitlement | Depth contract | None |
| Multi-model is escalation, not default | REUSE | Existing Intelligence proposal/runtime model | Multi-model protocol | No change |
| Independent first-round reasoning | EXTEND | Compatible with `CONSENSUS != CORRECTNESS` | Multi-model protocol | None |
| Majority vote cannot determine truth | REUSE | Existing epistemic invariant | Explicit invariant | No change |
| Evidence acquisition resolves model disagreement where possible | DERIVE | Claim/evidence and information-acquisition principles | Multi-model evidence phase | None |
| Workplace owns Meeting | REUSE | Workforce implementation architecture | Workplace integration | Ownership preserved |
| Intelligence enhances deliberation but does not own meeting | DERIVE | Workplace ownership + Intelligence capability | Meeting Intelligence integration | Ownership preserved |
| GPT/Gemini/Claude may consult a meeting without becoming Workers | DERIVE | Worker/provider separation | External intelligence participants | Ownership preserved |
| Canonical epistemic taxonomy must be reused | REUSE | Universal/BIOS downstream distinctions | Epistemic integrity | No parallel ontology |
| Intelligence cannot create authority | REUSE | BIOS/Workforce/Gateway boundaries | Authority integration | No change |
| User request is not authorization | REUSE | BIOS conformance boundary | Authority integration | No change |
| Decision != Execution | REUSE | Universal/Workforce boundary | Execution integration | No change |
| Execution != Outcome | REUSE | Workforce semantic chain | Outcome feedback | No change |
| Learning chain remains Workforce/Knowledge governed | REUSE | Workforce learning/improvement | Feedback integration | No competing learning owner |
| Model output cannot automatically become Knowledge | REUSE | Knowledge admission boundary | Learning/Knowledge integration | Knowledge ownership preserved |
| Memory is not channel-owned | DERIVE | Channel/interface boundary | Memory/continuity | No channel ownership |
| One Metatron architecture across users | EXTEND | Product architecture | Entitlement architecture | No semantic fork |
| Subscription controls resources, not truth discipline | DERIVE | Epistemic/authority invariants | Entitlement controls | No semantic weakening |
| Keyword heuristics cannot become primary semantic architecture | DERIVE | Frontier semantic capability + approved architecture | Runtime direction | Transitional heuristics only |

## Workforce Cognitive Substrate Sovereignty — 2026-09-12

Founder subsequently approved a stronger Worker cognition ownership requirement. This extends the shared Intelligence architecture without invalidating prior provider-neutral identity/routing closures.

| Approved requirement | Classification | Existing boundary | Effect |
|---|---|---|---|
| Worker paid external inference = 0 | EXTEND | Worker/provider identity separation + Founder Sovereignty scope | Worker cognition compute ownership strengthened |
| Worker origin first-class typed provenance | EXTEND | existing request/actor provenance | no new authority |
| METATRON_OWNED vs EXTERNAL_PAID | EXTEND | provider-neutral Intelligence Fabric | compute ownership only |
| Workforce provider keys absent after cutover | DERIVE | credential/security boundary | physical secret separation |
| Worker web retrieval without paid provider cognition | DERIVE | Tool/evidence acquisition | separates information from cognition |
| cognition capacity queues instead of paid fallback | EXTEND | actor/runtime capacity | no new Objective lifecycle |
| 1,000+ cognitive Worker proof | EXTEND | existing representation tests insufficient | new acceptance evidence |
| server-upgrade review before implementation | Founder execution constraint | execution governance | blocking gate |

## Canonical Boundaries Preserved

### Workforce

Preserved: Worker identity, participation, organization, role, capability, work, assignment, capacity, Workplace coordination, learning/improvement lineage.

### Workplace

Preserved: conversations, messages, threads, meetings, work queues, coordination records.

### Governance / Authorization

Preserved: authority/policy legitimacy and authorization semantics. Intelligence only carries/references applicable context.

### Gateway

Preserved: external boundary enforcement. Intelligence may request/use an authorized boundary but does not own it.

### Execution

Preserved: execution realization/infrastructure. Case and Workforce may coordinate/reference execution but do not redefine it.

### Observation / Outcome

Preserved: observation authority and distinct actual outcome state.

### Knowledge

Preserved: institutional Knowledge admission. Intelligence outputs and Workforce learning remain candidates until validly admitted.

### Universal / BIOS

Preserved: canonical grammar, epistemic distinctions, evidence/claim separation, authority boundaries, falsification/conformance semantics, and any stronger upstream rule.

## Upstream Verification Rule

Direct canonical `kelvinka38/metatron-institution/10_INTELLIGENCE/SOT.md` access was unavailable during the latest connector attempt. Therefore the following approved constructs are intentionally documented as runtime/product extensions rather than asserted new canonical primitives:

```text
INTELLIGENCE CASE
INFORMATION REQUIREMENT MODEL
ANALYTICAL PROTOCOL
FAST / ANALYZE / DEEP product depth labels
INFORMATION-VALUE prioritization heuristic
```

When direct upstream access is restored:

1. If an equivalent canonical primitive/term already exists, rename/reuse it.
2. If an upstream rule is stricter, upstream wins.
3. If no conflict exists, these constructs remain valid downstream extensions.
4. No downstream document may use these constructs to seize external canonical ownership.

## Documentation Gate

The approved Final Proposal and Detailed Architecture are synchronized by this matrix. Runtime implementation is not authorized merely by the existence of these docs; normal implementation/contract/test/deployment gates remain applicable.
