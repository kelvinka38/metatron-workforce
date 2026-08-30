# METATRON — INTELLIGENCE ARCHITECTURE FINAL PROPOSAL

## Status

**FOUNDER APPROVED — DETAILED DOCUMENTATION BASELINE**

This document is the approved product/engineering architecture target for Metatron Intelligence.

Canonical Source of Truth and policy remain authoritative. This proposal may extend product/runtime capability only inside canonical semantic, ownership, authority, evidence, execution, observation, knowledge, and Workforce boundaries. It does not create or supersede Universal primitives merely by defining an implementation/runtime construct.

Governing rule:

> **Canonical compliance is a constraint test, not an innovation veto.**

If canonical SOT already defines a semantic, the implementation reuses it. If a proposed feature contradicts SOT/policy or steals canonical ownership, it is rejected or redesigned. If it is compliant and creates meaningful product value, Metatron should build the best version of it.

Detailed architecture: `docs/ARCHITECTURE/METATRON_INTELLIGENCE_DETAILED_ARCHITECTURE.md`

Traceability/reconciliation matrix: `docs/ARCHITECTURE/METATRON_INTELLIGENCE_TRACEABILITY_MATRIX.md`

## 1. Product Definition

Metatron SHALL NOT compete with frontier models at being a general-purpose language model.

Metatron Intelligence is the provider-neutral institutional intelligence capability that connects frontier cognition with the Human's real context, evidence, data, organizational logic, Knowledge, Workforce, Tools, history, authority context, and observed outcomes so that natural-language requests can become evidence-grounded analysis and, when institutionally authorized, legitimate work.

The shortest product model is:

```text
UNDERSTAND
    ↓
KNOW
    ↓
MISSING?
 ┌──┴──┐
YES    NO
 │      │
GET     │
 │      │
 └──┬───┘
    ↓
REASON
    ↓
ANSWER / DECIDE
    ↓
ACT — when authorized
    ↓
OBSERVE
    ↓
LEARN
```

Each step remains owned by its correct canonical domain.

## 2. Hard Semantic Boundaries

```text
LLM               != METATRON
LLM               != WORKER
MODEL PROVIDER    != INSTITUTIONAL ROLE

INTELLIGENCE      != KNOWLEDGE
INTELLIGENCE      != AUTHORITY

CAPABILITY        != AUTHORITY
INTENT            != AUTHORIZATION

CLAIM             != EVIDENCE
MODEL_BELIEF      != TRUTH
CONFIDENCE        != EVIDENCE
CONSENSUS         != CORRECTNESS

PROPOSAL          != ASSIGNMENT
DECISION          != EXECUTION
EXECUTION         != OUTCOME
EXECUTION_SUCCESS != OUTCOME_SUCCESS
```

No downstream feature may collapse these distinctions.

## 3. Human Interaction — Frontier Semantic Interface

Human interaction SHALL remain natural-language-first.

Metatron SHALL NOT build a competing general-purpose multilingual NLP, translation, slang-resolution, typo-correction, or conversational semantic engine when frontier models already provide that capability adequately.

The Human may speak naturally in Vietnamese, English, mixed languages, slang, shorthand, typo-heavy text, or other supported languages. A frontier model such as ChatGPT, Gemini, Claude, a future model, or a compliant local model provides semantic interpretation and normalization.

```text
HUMAN
  │ natural language / multilingual / slang / typo
  ▼
FRONTIER MODEL
  │ semantic interpretation
  │ intent/context normalization
  ▼
CANONICAL REQUEST / OBJECTIVE
  ▼
NEXT METATRON STEP
```

The frontier semantic layer may interpret what the Human means, but it does not become Source of Truth, authority, business data, Worker identity, or execution evidence.

At output time, frontier models may also express a governed result naturally back to the Human.

## 4. Three Forms of Computation

Metatron SHALL use the appropriate form of computation rather than forcing every problem through an LLM.

### 4.1 Deterministic computation

Use deterministic systems for calculations, retrieval, filtering, aggregation, reconciliation, API/database queries, validation, and machine-checkable rules when sufficient.

### 4.2 Frontier cognition

Use frontier models for language understanding, semantic ambiguity, general reasoning, synthesis, critique, planning, interpretation, and natural expression.

### 4.3 Institutional intelligence

Use Metatron's institutional context: Knowledge, evidence, history, organization, Workers, Tools, authority context, prior work, decisions, observations, and learning.

The strongest path may combine all three:

```text
DETERMINISTIC
      +
FRONTIER
      +
INSTITUTIONAL
      ↓
GOVERNED RESULT
```

## 5. Intelligence Case

Metatron SHALL use an **Intelligence Case** as the preferred runtime coordination abstraction for a bounded, stateful intelligence problem.

The Intelligence Case is an application/runtime construct unless and until canonical SOT explicitly assigns it broader semantic status. It does not become a new Universal primitive merely because it exists in implementation.

Purpose:

> Preserve continuity across turns, information acquisition, reasoning, Workers, Workplace meetings, external systems, decisions, execution references, observed outcomes, and future follow-up without stealing authoritative ownership from the domains that own those states.

Proposed structure:

```text
INTELLIGENCE CASE
│
├── Identity
│   ├── case_id
│   ├── requester
│   └── scope
├── Objective
├── Context / context_refs
├── Information Requirements
│   ├── required
│   ├── available
│   ├── missing
│   └── unresolved
├── Evidence References
├── Epistemic State
│   ├── assumptions
│   ├── hypotheses
│   ├── unknowns
│   └── contradictions
├── Reasoning Artifacts
├── Conclusions
├── Recommendations
└── External Institutional References
    ├── worker_ref
    ├── meeting_ref
    ├── decision_ref
    ├── authorization_ref
    ├── execution_ref
    ├── observation_ref
    ├── outcome_ref
    └── knowledge_ref
```

Ownership invariants:

```text
CASE REFERENCES WORKER        — CASE DOES NOT OWN WORKER
CASE REFERENCES MEETING       — CASE DOES NOT OWN MEETING
CASE REFERENCES AUTHORIZATION — CASE DOES NOT CREATE AUTHORITY
CASE REFERENCES EXECUTION     — CASE DOES NOT OWN EXECUTION
CASE REFERENCES OUTCOME       — CASE DOES NOT REDEFINE OUTCOME
CASE REFERENCES KNOWLEDGE     — CASE DOES NOT BYPASS KNOWLEDGE ADMISSION
```

## 6. Information Requirements and Evidence Acquisition

Every non-trivial Case SHALL reason explicitly about what must be known and whether sufficient information exists.

```text
OBJECTIVE
    ↓
WHAT MUST BE KNOWN?
    ↓
DO WE KNOW ENOUGH?
   / \
 YES  NO
 │     │
 │     ▼
 │  INFORMATION REQUIREMENT
 │     │
 │     ├── already known?
 │     ├── validated Knowledge?
 │     ├── institutional artifact?
 │     ├── connected system / API / DB?
 │     ├── Worker observation?
 │     ├── authorized external source?
 │     ├── web/research?
 │     └── only Human can provide?
 │
 │     ▼
 │  ACQUIRE
 │     │
 └─────┘
    ↓
ANALYZE
```

Governing principle:

> **If Metatron can reliably obtain required information within authority, policy, cost, latency, and risk limits, obtain it before unnecessarily asking the Human.**

The Human should be asked when only the Human can supply the information, an assumption requires explicit Human choice, access/authorization or cost/risk consent is needed, or materially conflicting sources cannot be resolved safely.

Metatron SHALL NOT blindly retrieve every missing fact. It SHOULD prioritize missing information by expected impact on the conclusion relative to acquisition cost, latency, and risk.

## 7. Analytical Protocols

Metatron MAY define reusable analytical protocols such as:

```text
AUDIT
COMPARE
ROOT_CAUSE
PERFORMANCE
FORECAST
INVESTMENT
INCIDENT
RISK
IMPROVEMENT
DECISION
```

Protocols are composable. They define reusable evidence requirements, applicable analytical logic, and expected output structure. They do not replace frontier semantic understanding and do not redefine Universal/BIOS grammar or canonical epistemic semantics.

A Human request may resolve to one or more protocols without requiring keyword matching as the core semantic architecture.

## 8. Progressive Intelligence Depth

Metatron SHALL support progressive intelligence depth. Exact commercial names remain product decisions; architecture uses:

```text
FAST
ANALYZE
DEEP
```

### FAST

For casual conversation, translation, general knowledge, brainstorming, simple explanation, or other low-consequence requests. Typical path may be direct frontier cognition with minimal orchestration.

### ANALYZE

For problems requiring the Human's or institution's real context, data, evidence, deterministic computation, analytical protocols, and governed reasoning.

### DEEP

For persistent investigation with additional context, retrieval, evidence breadth, hypothesis exploration, falsification, contradiction analysis, reasoning rounds, and larger resource budgets.

**Deep does not mean call every model.**

User-selected depth establishes the requested reasoning contract. Subscription/entitlement establishes the available ceiling. Metatron optimizes execution inside that contract.

## 9. Intelligence Fabric

The shared, provider-neutral **Intelligence Fabric** remains the execution capability for frontier reasoning.

```text
INTELLIGENCE
     │
     └── INTELLIGENCE FABRIC
              │
        ┌─────┼─────┐
        ▼     ▼     ▼
       GPT  GEMINI CLAUDE ...
```

Provider identity is replaceable and independent from Worker identity.

An Intelligence request may include requester, objective, context, available evidence, required capability, consequence/risk, latency budget, resource budget, authority context, and required output.

The Fabric SHALL NOT establish `1 WORKER = 1 LLM SESSION`.

## 10. Multi-Intelligence Reasoning

Multi-model collaboration is a first-class escalation capability, not the default path.

Where independent review matters, Round 1 SHOULD use the same objective and evidence package while keeping providers independent to reduce anchoring.

Metatron then normalizes claims, evidence used, assumptions, hypotheses, unknowns, risks, recommendations, and confidence; identifies agreements, contradictions, unsupported claims, and information gaps; acquires resolvable evidence; and MAY run targeted challenge/revision rounds.

Stop conditions include sufficient evidence, material convergence, authority decision, budget exhaustion, or negligible additional information value.

Unresolved disagreement SHALL remain explicit.

```text
CONSENSUS != CORRECTNESS
```

A minority conclusion may prevail when evidence/reasoning is stronger.

## 11. Workforce and Worker Identity

A Worker is a persistent institutional actor, not an LLM persona or provider session.

Workers may possess/reference identity, participation, organization, role, capability, authority, work, assignment, context, capacity, Knowledge access, Tool access, work history, evidence, and learning lineage according to Workforce SOT.

When reasoning is required:

```text
WORKER
   ↓
requests intelligence
   ↓
INTELLIGENCE FABRIC
```

The provider used does not change Worker identity.

## 12. Workplace and Meetings

Workplace retains ownership of conversations, messages, threads, meetings, work queues, and coordination records according to Workforce architecture.

Intelligence MAY provide powerful deliberation capability to a Workplace meeting without owning the meeting.

```text
WORKPLACE
   └── MEETING
        ├── Human
        ├── Worker A
        ├── Worker B
        └── requests Intelligence when needed
```

Institutional deliberation may include independent participant positions, evidence comparison, disagreement detection, missing-information acquisition, targeted challenge, and revised positions.

External models may participate through the Intelligence Fabric as external intelligence providers. They do not become institutional officers or Workers.

## 13. Epistemic Integrity

Detailed implementation SHALL reuse canonical Universal/BIOS epistemic definitions and SHALL NOT create a competing ontology.

Existing downstream architecture recognizes distinctions including:

```text
FACT
OBSERVATION
EVIDENCE
INFERENCE
HYPOTHESIS
ASSUMPTION
UNKNOWN
```

At minimum:

```text
MODEL OUTPUT      != FACT
WORKER STATEMENT  != AUTOMATICALLY VERIFIED FACT
HUMAN STATEMENT   != AUTOMATICALLY VERIFIED FACT
CLAIM             != EVIDENCE
CONFIDENCE        != EVIDENCE
CONSENSUS         != CORRECTNESS
UNKNOWN           != TRUE
UNKNOWN           != FALSE
```

Uncertainty SHALL be preserved rather than cosmetically resolved.

## 14. Universal / BIOS Boundary

Universal/BIOS supplies applicable canonical grammar, logic, evidence discipline, contradiction/falsification, analysis/improvement semantics, conformance, and consequence-sensitive governance according to canonical SOT.

BIOS is not an LLM. It SHALL NOT become a chatbot, language translator, slang engine, provider prompt, evidence manufacturer, authority source, Worker, Knowledge owner, or execution authority.

Full formalism SHALL NOT be forced onto ordinary casual conversation where canonical policy does not require it.

## 15. Authority and Execution

Intelligence does not create authority.

```text
USER REQUEST       != AUTHORIZATION
LLM RECOMMENDATION != AUTHORITY
```

Consequential institutional flow remains:

```text
INTELLIGENCE
    ↓
RECOMMENDATION / APPLICABLE DECISION
    ↓
ASSIGNMENT / AUTHORIZATION
    ↓
GATEWAY where required
    ↓
EXECUTION
    ↓
OBSERVATION
    ↓
OUTCOME
```

An Intelligence Case may retain references to these states but does not own them.

## 16. Learning and Knowledge

Metatron SHALL reuse the existing Workforce learning chain rather than creating a competing intelligence-owned learning ontology:

```text
EXECUTION
 ↓
OUTCOME
 ↓
EVIDENCE
 ↓
ATTRIBUTION
 ↓
EXPERIENCE
 ↓
REFLECTION
 ↓
LEARNING
 ↓
IMPROVEMENT
```

Intelligence may contribute reasoning artifacts, hypotheses, recommendations, and learning candidates. Unverified model output MUST NOT automatically become institutional Knowledge. Knowledge admission remains with the canonical Knowledge boundary.

## 17. Memory and Channels

Memory SHALL NOT be channel-owned.

Telegram, Web, Mobile, Zalo, Voice, API, and future channels are interface/transport surfaces. They do not become Intelligence, Knowledge, Worker identity, institutional memory owner, or authority source.

Cross-channel continuity may reference the same institutional context, Case, Knowledge, work history, evidence, decisions, and learning where access and authority permit.

## 18. Commercial / Entitlement Architecture

There is one Metatron architecture. Founder, customer, Business, and Enterprise users do not receive different semantic systems.

Available capabilities result from identity, Business/organization context, institutional authority, and subscription entitlement.

Plans may vary compute budget, model tier, context size, Case retention, memory, connectors, retrieval volume, Workers, meetings, reasoning rounds, parallelism, multi-model access, execution volume, and enterprise governance.

They MUST NOT vary canonical truth discipline, provenance integrity, authority separation, or epistemic correctness.

> **The quality floor remains correct; the depth ceiling changes by plan.**

## 19. Runtime Direction

The architecture SHALL progressively move away from deterministic keyword/continuation heuristics as the core semantic layer.

Target flow:

```text
natural language
 ↓
frontier semantic interpretation
 ↓
canonical objective / Case
 ↓
information requirements
 ↓
deterministic Knowledge / Tools where sufficient
 ↓
Intelligence Fabric where reasoning is required
 ↓
Universal / BIOS conformance where applicable
 ↓
governed result
```

Keyword heuristics may remain only as transitional, defensive, or explicitly deterministic rules where appropriate.

## 20. Architectural Invariants

1. Canonical SOT/policy always wins over downstream implementation.
2. Canonical compliance constrains innovation; it does not prohibit compliant innovation.
3. No new canonical primitive without demonstrated need and correct upstream governance.
4. A feature may exist as an application/runtime construct without becoming a Universal primitive.
5. No feature may steal authoritative state from its canonical owner.
6. Frontier language intelligence must not be unnecessarily recreated with keyword rules.
7. Use deterministic computation where deterministic computation is sufficient.
8. Workers are persistent institutional actors, never LLM personas.
9. Provider identity and Worker identity remain independent.
10. Intelligence Fabric remains provider-neutral shared capability.
11. Information acquisition precedes unnecessary intelligence expenditure.
12. Retrieve what Metatron can reliably retrieve before unnecessarily asking the Human.
13. Missing information remains missing; it must not be hallucinated.
14. Evidence and reasoning remain distinct.
15. Model belief and consensus do not establish truth.
16. Intelligence Case coordinates intelligence state but does not absorb canonical domain ownership.
17. Workplace retains ownership of meetings.
18. Intelligence may enhance deliberation without owning the meeting.
19. Multi-model collaboration is an escalation capability, not a default requirement.
20. Independent reasoning precedes cross-model influence where independent review matters.
21. Intelligence cannot manufacture authority.
22. Intent, capability, authority, authorization, assignment, execution, observation, and outcome remain distinct.
23. Model output/learning candidates cannot automatically become institutional Knowledge.
24. Channels do not own Intelligence, institutional memory, or authority.
25. Human-requested intelligence depth and entitlement define the contract; Metatron optimizes execution inside it.
26. Subscription controls resources/capabilities, not truth semantics.
27. Every expensive component must create value beyond simply opening a frontier chatbot.
28. Do not add agent swarms or abstractions merely because they are technically possible.
29. The simplest SOT-compliant architecture that maximizes user value wins.

## 21. Acceptance Test

For every future Intelligence feature, ask:

> **Why is this better inside Metatron than simply asking ChatGPT, Gemini, or Claude?**

If the answer is only "because Metatron also calls an LLM", reject it.

Metatron earns its value when it brings together the Human's context, history, evidence, data, Business, Knowledge, organizational logic, Workers, Tools, authority, prior decisions, real outcomes, persistent investigation, institutional deliberation, authorized execution, and institutional learning.

## 22. Final Principle

> **Workers do not chat with AI. Workers have access to intelligence.**

> **Humans speak naturally; frontier models provide semantic interpretation. Metatron SHALL NOT rebuild general multilingual/slang/typo NLP unnecessarily.**

> **Information acquisition precedes unnecessary intelligence expenditure.**

> **SOT defines the boundary. Inside that boundary, build the best possible product.**
