# METATRON INTELLIGENCE — DETAILED ARCHITECTURE

## Status

**FOUNDER APPROVED — DETAILED ARCHITECTURE BASELINE**

This document elaborates the approved Intelligence architecture without redefining canonical SOT or policy. Canonical ownership, authority, epistemic semantics, execution, observation, Knowledge, Workplace, and Workforce boundaries remain authoritative.

## 1. Governing Order

```text
CANONICAL SOT / POLICY
        ↓
APPROVED FINAL PROPOSAL
        ↓
DETAILED ARCHITECTURE
        ↓
CONTRACT / STATE / EVENT DESIGN
        ↓
IMPLEMENTATION
```

A detailed feature is valid when it either reuses a canonical semantic or remains a non-conflicting product/runtime construct inside the canonical boundary.

## 2. Human Semantic Interface

### 2.1 Requirement

Metatron SHALL rely on frontier-model semantic capability for general multilingual understanding, translation, slang, colloquial language, typo correction, shorthand, implicit phrasing, and natural-language normalization.

Metatron SHALL NOT build a competing general-purpose NLP/translation subsystem for those capabilities when frontier models provide them adequately.

### 2.2 Input contract

Conceptual input:

```text
HUMAN_UTTERANCE
├── raw_text
├── channel_context
├── actor_identity_ref
├── conversation_context_ref
└── access_context_ref
```

Frontier semantic interpretation produces a normalized request such as:

```text
NORMALIZED_REQUEST
├── objective
├── target / subject
├── constraints
├── requested_depth
├── requested_output
├── explicit_assumptions
├── explicit_prohibitions
├── temporal_context
└── unresolved_semantic_ambiguity
```

The exact serialized schema is an implementation detail and MUST reuse any canonical naming if upstream SOT defines equivalent fields.

### 2.3 Semantic boundary

The semantic interpreter may interpret language but MUST NOT manufacture:

```text
FACT
EVIDENCE
AUTHORITY
AUTHORIZATION
WORKER IDENTITY
EXECUTION EVIDENCE
INSTITUTIONAL KNOWLEDGE
```

Semantic ambiguity that materially affects a consequential request must remain explicit or be resolved through context/Human clarification.

## 3. Intelligence Case Lifecycle

### 3.1 Purpose

An Intelligence Case is the runtime coordination record for one bounded intelligence problem.

It enables continuity beyond stateless request/response and allows later reasoning to understand what was asked, what was known, what remained unknown, which evidence was acquired, which conclusions were reached, and which external institutional states are related.

### 3.2 Lifecycle

```text
OPEN
 ↓
UNDERSTANDING
 ↓
INFORMATION_ASSESSMENT
 ↓
ACQUISITION
 ↓
REASONING
 ↓
RESULT_READY
 ↓
FOLLOW_UP / WAITING_ON_EXTERNAL_STATE
 ↓
REOPENED / REASSESSMENT
 ↓
RESOLVED
```

Lifecycle names are implementation-level unless canonical lifecycle terminology exists upstream.

A Case MAY remain open when the answer depends on an external future state, missing evidence, unresolved disagreement, execution outcome, or later Human input.

### 3.3 Case ownership rules

Case-owned runtime state may include objective, reasoning state, information requirements, Case-local assumptions/hypotheses, reasoning artifacts, conclusions, recommendations, and references.

Case MUST NOT become authoritative owner for Worker, Meeting, Authorization, Execution, Observation, Outcome, or Knowledge state.

## 4. Information Requirement Model

Each Case may maintain a set of information requirements.

Conceptual structure:

```text
INFORMATION_REQUIREMENT
├── requirement_id
├── question / required datum
├── reason_required
├── status
│   ├── SATISFIED
│   ├── MISSING
│   ├── CONFLICTED
│   ├── UNRESOLVABLE
│   └── DEFERRED
├── preferred_source_classes
├── evidence_refs
├── freshness_requirement
├── confidence / quality requirement
├── acquisition_cost_hint
├── latency_hint
├── authority / access requirement
└── impact_if_unknown
```

Exact field names are not canonical unless upstream SOT says so.

## 5. Evidence Acquisition Strategy

Metatron SHOULD prefer reliable lower-cost sources before consuming unnecessary frontier reasoning.

Typical acquisition order:

```text
1. Validated institutional Knowledge
2. Existing institutional artifacts / records
3. Deterministic connected systems / APIs / databases
4. Authorized Worker observations or work products
5. Authorized external structured data
6. Web / external research
7. Frontier reasoning where reasoning is actually required
```

This order is a planning preference, not a rigid universal rule. Freshness, authority, reliability, latency, and consequence may change the order.

### 5.1 Ask-the-Human rule

Ask the Human when:

- only the Human can provide the fact or preference;
- an ambiguity materially changes the objective;
- the Human must choose an assumption;
- authority/access/consent is required;
- acquisition cost/risk needs explicit approval;
- materially conflicting evidence remains unresolved.

Do not ask the Human for data Metatron can retrieve safely and reliably itself.

### 5.2 Information value

Metatron SHOULD prioritize evidence likely to change the conclusion before low-value completeness work.

Conceptual heuristic:

```text
EXPECTED INFORMATION VALUE
≈ expected decision / conclusion impact
  divided by
  acquisition cost + latency + risk
```

This is a reasoning principle, not a required literal numeric formula.

## 6. Analytical Protocol Model

Protocols are reusable analytical procedures rather than keyword intents.

A protocol may specify:

```text
ANALYTICAL_PROTOCOL
├── objective class
├── minimum information requirements
├── optional information requirements
├── deterministic operations
├── reasoning operations
├── falsification / contradiction checks
├── evidence quality expectations
└── output contract
```

Protocols MAY be composed.

Example:

```text
PERFORMANCE
+
TEMPORAL_COMPARE
+
ROOT_CAUSE
+
IMPROVEMENT
```

Semantic interpretation selects or constructs the appropriate analytical path; deterministic string matching MUST NOT be the principal architecture for understanding Human intent.

## 7. Computation Planner

The Case execution planner chooses among three computation classes.

### 7.1 Deterministic

Use for calculable/retrievable operations such as metrics, formulas, reconciliation, filtering, transformations, data validation, and exact API/system access.

### 7.2 Frontier cognition

Use for semantic understanding, ambiguous interpretation, general reasoning, synthesis, critique, planning, comparison, explanation, and other non-deterministic cognitive tasks.

### 7.3 Institutional intelligence

Use institutional context and capabilities: Knowledge, Workers, Workplace context, Tools, prior work, evidence, organizational relationships, authority context, outcomes, and learning.

The planner SHOULD minimize unnecessary frontier calls without degrading requested intelligence depth or correctness.

## 8. Progressive Depth Contract

### FAST

Goal: low-friction conversational utility.

Typical resources:

```text
frontier semantic/cognitive response
minimal institutional orchestration
minimal or no Case persistence where unnecessary
```

### ANALYZE

Goal: evidence-grounded analysis connected to reality.

Typical resources:

```text
Case
context retrieval
information requirements
deterministic computation
connected systems / Knowledge
frontier reasoning
applicable BIOS / conformance
```

### DEEP

Goal: persistent investigation and stronger falsification.

Typical resources:

```text
larger context
iterative acquisition
multiple hypotheses
contradiction analysis
additional reasoning rounds
higher-value evidence search
possible independent model review
longer Case continuity
```

Depth does not imply a fixed number of model calls.

## 9. Intelligence Fabric Contract

The Intelligence Fabric is provider-neutral shared cognition infrastructure.

Conceptual request:

```text
INTELLIGENCE_REQUEST
├── requester_ref
├── objective
├── context_refs
├── evidence_refs
├── required_capability
├── consequence / risk
├── latency_budget
├── resource_budget
├── authority_context_ref
├── collaboration_mode
└── output_contract
```

Conceptual result:

```text
INTELLIGENCE_RESULT
├── result_id
├── provider_attribution
├── reasoning / synthesis artifact refs
├── evidence_refs_used
├── claims / conclusions
├── uncertainty / unresolved conflicts
├── governance / conformance result
└── resource / execution metadata
```

No provider-specific conversational session becomes Worker identity or institutional authority.

## 10. Multi-Model Deliberation Protocol

### Phase A — Independent proposals

Each participating provider receives equivalent objective/context/evidence sufficient for independent analysis.

### Phase B — Normalization

Normalize provider outputs into comparable claims, evidence use, assumptions, hypotheses, unknowns, risks, recommendations, and confidence.

### Phase C — Contradiction analysis

Identify:

```text
agreement
material disagreement
unsupported claims
conflicting assumptions
missing evidence
reasoning defects
```

### Phase D — Evidence acquisition

When disagreement is resolvable through reality, retrieve evidence before consuming more debate.

### Phase E — Targeted challenge

Providers may receive specific disputed questions and canonical evidence for revision.

### Phase F — Synthesis

Produce one governed result while preserving unresolved disagreement.

### Stop rules

Stop when evidence is sufficient, marginal value is low, budget is exhausted, required authority decides, or unresolved uncertainty must remain unresolved.

Majority agreement MUST NOT be treated as proof.

## 11. Workplace Deliberation Integration

Workplace owns meetings and communication state.

A Workplace Meeting may request Intelligence capabilities for:

```text
participant position extraction
shared evidence packaging
disagreement detection
missing-information discovery
independent external review
challenge questions
synthesis support
```

Workers remain actual institutional participants. External providers remain external intelligence sources.

Meeting decisions, records, actions, and coordination remain owned according to canonical Workplace/Workforce semantics.

## 12. Workforce Integration

Workers may request Intelligence when work requires cognition beyond deterministic/validated sources.

A Worker request SHALL retain Worker attribution and applicable work/assignment/authority context.

Intelligence does not upgrade capability into authority and does not replace the Worker as accountable institutional actor.

## 13. BIOS / Universal Integration

BIOS/Universal conformance applies according to canonical requirements and consequence.

Applicable controls may include:

```text
canonical grammar
claim/evidence separation
contradiction
falsification
unknown handling
reasoning integrity
applicable authority boundaries
improvement discipline
```

Casual Human language does not need to become a formal BIOS artifact merely to exist.

Frontier semantic interpretation should occur before any formal downstream representation where natural-language understanding is needed.

## 14. Authority / Execution Integration

No semantic or reasoning output constitutes authorization by itself.

Consequential path remains:

```text
Case conclusion / recommendation
 ↓
applicable institutional decision
 ↓
assignment / authorization
 ↓
Gateway where boundary crossing applies
 ↓
Execution
 ↓
Observation
 ↓
Outcome
```

The Case stores references, not authoritative copies, of those states.

## 15. Outcome Feedback and Learning

Observed outcomes may be linked back to the originating Case to test prior assumptions, hypotheses, and recommendations.

Example:

```text
CASE HYPOTHESIS
 ↓
RECOMMENDATION
 ↓
AUTHORIZED EXECUTION
 ↓
OBSERVED OUTCOME
 ↓
EXPECTED vs ACTUAL
 ↓
EXPERIENCE / REFLECTION
 ↓
LEARNING CANDIDATE
 ↓
KNOWLEDGE ADMISSION if valid
```

This provides closed-loop intelligence while preserving canonical ownership of Execution, Observation, Workforce learning, and Knowledge admission.

## 16. Memory and Continuity

Intelligence continuity is independent of channel.

A Case may be resumed from another channel when identity/access permits.

Channel adapters SHALL not become authoritative owners of Case state, Knowledge, Worker state, or institutional memory.

## 17. Failure Semantics

The system SHALL prefer explicit failure/uncertainty over fabricated completion.

Important valid states include:

```text
insufficient evidence
conflicting evidence
unknown
access denied
not authorized
source unavailable
provider unavailable
budget exhausted
unresolved disagreement
execution not yet observed
outcome not yet known
```

These are valid results, not errors to be hidden through confident language.

## 18. Product / Entitlement Controls

Entitlement may control:

```text
model access
reasoning depth
context limits
Case retention
retrieval budget
connector access
Worker access
meeting intelligence
multi-model review
parallelism
execution volume
governance/audit capabilities
```

Entitlement MUST NOT weaken canonical truth/evidence/authority rules.

## 19. Non-Goals

This architecture explicitly does not aim to build:

```text
a new general-purpose multilingual NLP engine
a translation engine competing with frontier models
a Worker-per-LLM architecture
an LLM role-play organization
an always-on multi-model swarm
a parallel epistemic ontology
a new authority source
a mega-domain that owns Meeting/Execution/Outcome/Knowledge
a channel-specific intelligence brain
```

## 20. Detailed Acceptance Criteria

A future implementation is conformant only if all of the following are true:

1. Human multilingual/slang/typo understanding is provided primarily through frontier semantic capability rather than expanding keyword heuristics.
2. Case continuity exists for non-trivial stateful analysis.
3. Missing information is explicit and can trigger acquisition.
4. Deterministic computation is used where appropriate.
5. Frontier calls remain provider-neutral behind Intelligence Fabric.
6. Worker identity never collapses into provider/session identity.
7. Workplace retains Meeting ownership.
8. Intelligence cannot manufacture authority or authorization.
9. Execution and Outcome remain external canonical states linked by references.
10. Model outputs remain epistemically classified and evidence-linked.
11. Multi-model review preserves independence and does not vote truth into existence.
12. Learning candidates cannot bypass Knowledge admission.
13. Subscription changes resource ceiling rather than canonical correctness.
14. Channels remain interfaces, not intelligence owners.
15. Every added subsystem demonstrates value beyond direct frontier chat.

## 21. Implementation Sequencing Rule

Detailed implementation planning should proceed in dependency order rather than by adding more heuristics:

```text
1. normalized semantic request boundary
2. Intelligence Case continuity
3. information-requirement state
4. deterministic / Knowledge / Tool acquisition integration
5. analytical protocol composition
6. Intelligence Fabric integration
7. governed result / epistemic output
8. Workplace / Worker deliberation integration
9. multi-model escalation
10. outcome feedback / learning references
```

This sequence does not authorize code by itself. Implementation must still follow the repository's normal approval, contract, test, and deployment gates.
