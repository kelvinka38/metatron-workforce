# METATRON — INTELLIGENCE ARCHITECTURE FINAL PROPOSAL

## Status

**RECONCILED — IMPLEMENTATION BASELINE**

This proposal has been reconciled against the canonical `10_INTELLIGENCE/SOT.md` in `kelvinka38/metatron-institution`, the Universal SOT, the Workforce execution plan, the existing Workplace/Communication boundary, BIOS governance semantics, and the Gateway ownership/execution boundary.

The institutional canonical source remains `kelvinka38/metatron-institution/10_INTELLIGENCE/SOT.md`. This document is the Workforce engineering interpretation and must not redefine that SOT.

## 1. Executive Decision

Metatron SHALL NOT model a Worker as an LLM chat session.

A Worker is an institutional actor with access to Workplace/communication, Knowledge, Tools, Other Workers, and shared Intelligence resources when reasoning is required.

LLMs (ChatGPT, Claude, Gemini, future models, or local models) are implementations behind a shared **Intelligence Fabric**. They are not Workers, not authorities, and not the communication bus.

## 2. Canonical Architecture

```text
                         HUMAN
                           |
                           v
                  WORKPLACE / INTERFACE
                           |
                           v
                       WORKFORCE
                           |
             +-------------+-------------+
             |             |             |
             v             v             v
          Worker A      Worker B      Worker N
             |             |             |
             +-------------+-------------+
                           |
              +------------+------------+
              |            |            |
              v            v            v
          KNOWLEDGE      TOOLS       WORKER
           FABRIC        FABRIC    COMMUNICATION
              |            |            |
              +------------+------------+
                           |
                           v
                  INTELLIGENCE FABRIC
                           |
              +------------+------------+
              |            |            |
              v            v            v
           ChatGPT       Claude       Gemini
              |            |            |
              +------------+------------+
                           |
                           v
                    BIOS GOVERNANCE
                           |
              +------------+------------+
              |            |            |
              v            v            v
           REASON       DECIDE        ACT
              |            |            |
              +------------+------------+
                           |
                           v
                        OBSERVE
                           |
                           v
                         LEARN
                           |
                           v
                    KNOWLEDGE FABRIC
```

## 3. Human Interaction

Human interaction SHALL remain natural-language-first.

Example:

```text
Telegram:
"audit G4 gateway"
```

The Human does not need to select a Worker, provider, model, or tool manually. Metatron resolves the request into the appropriate institutional workflow.

```text
Human
  -> Telegram / Workplace
  -> intent + target
  -> Workforce
  -> Knowledge / Tools / Workers
  -> Intelligence when required
  -> BIOS validation
  -> Gateway / Execution when authorized
  -> evidence / result
  -> Human
```

Telegram is only an interface adapter. It is not the source of truth, authority boundary, Gateway, Worker, or Intelligence Fabric.

## 4. Worker ↔ Worker

Worker-to-Worker communication SHALL NOT require an LLM by default. Workers may directly exchange structured messages, requests, assignments, artifacts, observations, evidence references, status, and results.

An LLM is invoked only when the interaction requires reasoning, synthesis, critique, planning, or another intelligence capability.

## 5. Worker → Knowledge / Tools

Workers SHALL acquire information before consuming scarce reasoning capacity whenever deterministic or validated sources can answer the requirement reliably enough.

Preferred acquisition order:

```text
1. Validated knowledge
2. Institutional artifacts / Library
3. Deterministic APIs / systems
4. Worker observations
5. Authorized external data
6. Web search / retrieval
7. LLM reasoning when required
```

Web access and external tools remain capability/authority controlled.

## 6. Intelligence Fabric

Workers SHALL request intelligence through a provider-neutral capability contract, not a permanent provider-specific conversation.

```text
INTELLIGENCE_REQUEST
├── requester
├── objective
├── context
├── available evidence
├── required capability
├── consequence / risk
├── latency budget
├── cost / resource budget
├── authority context
└── required output
```

The Intelligence Fabric decides whether reasoning is necessary and allocates shared intelligence capacity.

The architecture MUST NOT establish:

```text
1 WORKER = 1 LLM SESSION
```

Instead:

```text
N WORKERS
   ↓
SHARED INTELLIGENCE CAPACITY
```

## 7. Progressive Intelligence Allocation

```text
Simple / deterministic
    -> tools / retrieval

Normal reasoning
    -> one model

Uncertain / contested
    -> independent second opinion

High consequence
    -> multi-model review

Critical / irreversible
    -> multi-model + BIOS + required human authority
```

Provider quotas, rate limits, token limits, concurrency, cost, latency, and availability are shared capacity constraints.

## 8. Multi-Model Collaboration

Multi-model collaboration is an escalation mechanism, not the default path.

```text
Request
  -> independent proposals
  -> evidence / assumptions / reasoning claims
  -> cross-review where required
  -> contradiction analysis
  -> BIOS conformance
  -> synthesis
  -> one consolidated governed output
```

Majority voting is not truth determination.

```text
CONSENSUS ≠ CORRECTNESS
```

A minority conclusion MAY become final when its evidence or reasoning is stronger.

Supported patterns include `SINGLE`, `LEAD + REVIEWER`, `PARALLEL + SYNTHESIS`, `ADVERSARIAL REVIEW`, and `INDEPENDENT SECOND OPINION`.

## 9. BIOS Governance

BIOS is not an LLM and is not replaced by an LLM provider.

BIOS governs reasoning, evidence integrity, authority, decision rights, falsification, and related institutional behavior according to its canonical SOT and upstream authority.

Reasoning depth is consequence/complexity/uncertainty dependent; INTELLIGENCE MUST NOT impose a fixed formal template on every conversation.

```text
CASUAL
  ↓
DISCUSSION
  ↓
REASONING
  ↓
DECISION
  ↓
EXECUTION
  ↓
IRREVERSIBLE / HIGH-CONSEQUENCE ACTION
```

This is progressive governance, not mandatory formalism for every sentence.

Material outputs MUST preserve epistemic distinctions such as `FACT`, `OBSERVATION`, `EVIDENCE`, `INFERENCE`, `HYPOTHESIS`, `ASSUMPTION`, and `UNKNOWN`.

Core invariants include:

```text
ASSUMPTION           != FACT
MODEL_BELIEF         != TRUTH
CONFIDENCE           != EVIDENCE
CONSENSUS            != CORRECTNESS
PLAN                 != EXECUTION
EXECUTION_SUCCESS    != OUTCOME_SUCCESS
ABSENCE_OF_EVIDENCE  != EVIDENCE_OF_ABSENCE
```

## 10. Knowledge / Learning

Institutional learning is represented through validated knowledge artifacts rather than dependence on external LLM retraining.

```text
OBSERVATION
   ↓
EVIDENCE
   ↓
VALIDATION
   ↓
KNOWLEDGE ARTIFACT
   ↓
KNOWLEDGE FABRIC
   ↓
FUTURE RETRIEVAL
```

Unverified model output MUST NOT automatically become institutional knowledge.

## 11. Authority / Execution Boundary

Intelligence does not create authority.

```text
INTELLIGENCE
   ↓
RECOMMENDATION / DECISION WITHIN AUTHORITY
   ↓
AUTHORIZATION
   ↓
GATEWAY WHEN EXTERNAL BOUNDARY CROSSING IS REQUIRED
   ↓
EXECUTION
   ↓
OBSERVATION / VERIFICATION
```

Telegram, LLM providers, and Intelligence MUST NOT bypass the applicable authorization or Gateway boundary. Gateway ownership, semantics, and gate definitions remain unchanged by this architecture.

## 12. Reconciliation Record

### Universal SOT

The architecture preserves the Universal distinctions required for downstream systems:

```text
REALITY ≠ MODEL
CAPABILITY ≠ AUTHORITY
CLAIM ≠ EVIDENCE
EVIDENCE ≠ TRUTH
DECISION ≠ EXECUTION
UNKNOWN ≠ TRUE
UNKNOWN ≠ FALSE
```

It follows the Universal derivation chain and does not create a new Universal primitive.

### Workforce Master Execution Plan

The architecture preserves the existing Workforce sequence and boundaries. Intelligence is a capability consumed by Workforce; it does not replace Workplace, Communication, Authorization, Execution, Learning, or Economic boundaries. The Workforce plan's requirements for identity, attribution, authorization, provenance, evidence, temporal validity, learning lineage, and economic evidence remain intact.

### BIOS

The architecture uses BIOS as governance/conformance and preserves dynamic reasoning depth, epistemic status, authority separation, and evidence discipline. It does not turn BIOS into a provider prompt or force formalism on casual conversation.

### Gateway

The architecture does not move Gateway ownership into Intelligence. Intelligence may use Gateway information and may request authorized external execution, but Gateway remains the external boundary enforcement layer.

### Institutional canonical source

The canonical Intelligence SOT is:

`kelvinka38/metatron-institution/10_INTELLIGENCE/SOT.md`

This Workforce document is subordinate engineering interpretation only.

## 13. Implementation Rule

Implementation SHALL proceed from the canonical Intelligence SOT into provider-neutral contracts and tests.

Provider transport, credentials, concrete models, and external API integrations remain implementation details behind the Intelligence Fabric.

The first implementation objective is **not** "connect every Worker to ChatGPT/Claude/Gemini". It is:

```text
INTELLIGENCE_REQUEST
        ↓
ROUTING / CAPACITY DECISION
        ↓
PROVIDER-NEUTRAL EXECUTION
        ↓
BIOS / AUTHORITY / VALIDATION
        ↓
GOVERNED RESULT
```

## 14. Architectural Principle

> **Workers do not chat with AI. Workers have access to intelligence.**

> **Information acquisition precedes intelligence expenditure.**

> **BIOS governs consequential cognition and action; it does not suppress ordinary human conversation.**
