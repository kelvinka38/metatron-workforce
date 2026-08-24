# METATRON — INTELLIGENCE ARCHITECTURE FINAL PROPOSAL

## Status

PROPOSAL — pending institutional reconciliation and approval.

This document is **not yet an SOT**. It is the final architectural proposal to be reconciled against the canonical Metatron SOT / Master Execution Plan before implementation becomes authoritative.

## 1. Executive Decision

Metatron SHALL NOT model a Worker as an LLM chat session.

A Worker is an institutional actor with access to:

1. Workplace / communication;
2. Knowledge;
3. Tools and external capabilities;
4. Other Workers;
5. Shared Intelligence resources when reasoning is required.

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

The Human does not need to select a Worker, provider, model, or tool manually.

Metatron resolves the request into the appropriate institutional workflow.

Conceptually:

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

Worker-to-Worker communication SHALL NOT require an LLM by default.

Workers may directly exchange:

- structured messages;
- requests;
- assignments;
- artifacts;
- observations;
- evidence references;
- status;
- results.

Example:

```text
Worker A
  -> structured request
  -> Worker B
  -> structured result
  -> Worker A
```

An LLM is invoked only when the interaction requires reasoning, synthesis, critique, planning, or another intelligence capability.

## 5. Worker → Knowledge

Workers SHALL be able to acquire information before consuming scarce reasoning capacity.

Preferred acquisition order:

```text
1. Existing validated knowledge
2. Local / institutional artifacts
3. Deterministic APIs and systems
4. Worker observations
5. Authorized external data
6. Web search / web retrieval
7. LLM reasoning when needed
```

Principle:

> Information acquisition precedes intelligence expenditure.

Web access is a capability, not an implicit authority. Access remains governed by Worker identity, role, policy, and available tools.

## 6. Knowledge Fabric

Knowledge SHALL be treated as a first-class Metatron capability.

Knowledge MAY originate from:

- canonical SOT;
- validated institutional documents;
- repository state;
- API observations;
- operational observations;
- external documentation;
- web research;
- validated Worker discoveries;
- prior outcomes.

Unverified model output SHALL NOT automatically become institutional knowledge.

Promotion path:

```text
Observation
  -> Evidence
  -> Validation
  -> Knowledge Artifact
  -> Knowledge Fabric
```

## 7. Intelligence Fabric

Workers SHALL request intelligence through a capability contract, not through provider-specific conversations.

Conceptual request:

```text
INTELLIGENCE_REQUEST

objective
context
available_evidence
required_capability
consequence_level
latency_budget
cost_budget
collaboration_policy
```

The Intelligence Fabric decides whether an LLM is necessary and, if so, which available intelligence resource should be used.

Provider identity is an implementation detail unless explicitly requested or required by policy.

## 8. Progressive Intelligence Allocation

LLM usage SHALL be demand-driven.

```text
Simple / deterministic
    -> tools / retrieval

Normal reasoning
    -> one model

Uncertain / contested
    -> second opinion

High-consequence reasoning
    -> multi-model collaboration

Critical / irreversible
    -> multi-model + BIOS + Human authorization where required
```

Therefore:

> 1,000 Workers MUST NOT imply 1,000 permanent LLM sessions.

LLM provider limits are shared infrastructure-capacity constraints handled by the Intelligence Fabric, not Worker-level architectural dependencies.

## 9. Provider Selection

The system SHALL support explicit and implicit provider selection.

Examples:

```text
"Claude, audit G4"
    -> Claude

"GPT + Claude + Gemini, audit G4"
    -> multi-model collaboration

"audit G4"
    -> Metatron selects the appropriate intelligence path
```

Provider selection MUST NOT bypass BIOS, authorization, Gateway, or institutional workflow.

## 10. Multi-Model Collaboration

Multi-model collaboration SHALL NOT be implemented as simple majority voting.

Required conceptual protocol:

```text
Request
  -> independent proposals
  -> evidence / assumptions / reasoning claims
  -> cross-review where required
  -> contradiction analysis
  -> BIOS conformance
  -> synthesis
  -> one consolidated response
```

Consensus is a collaboration mechanism, not proof of truth.

A minority proposal MAY become the final conclusion if its evidence and reasoning are stronger.

## 11. BIOS Governance

BIOS SHALL NOT be implemented merely as a large system prompt.

BIOS is a constitutional governance contract composed conceptually of:

```text
SEMANTICS
GRAMMAR
LOGIC
INVARIANTS
CONSTRAINTS
VALIDATOR
ENFORCEMENT
```

BIOS governs consequential reasoning and action, not ordinary conversation.

### Progressive governance

```text
CASUAL
  -> minimal governance

DISCUSSION
  -> light reasoning discipline

REASONING
  -> strict claim / evidence discipline

DECISION
  -> strict evidence / contradiction / authority checks

EXECUTION
  -> maximum governance
```

## 12. Core BIOS Invariants

At minimum, the governance model SHALL distinguish:

```text
ASSUMPTION           != FACT
MODEL_BELIEF         != TRUTH
CONFIDENCE           != EVIDENCE
CONSENSUS            != CORRECTNESS
PLAN                 != EXECUTION
EXECUTION_SUCCESS    != OUTCOME_SUCCESS
ABSENCE_OF_EVIDENCE  != EVIDENCE_OF_ABSENCE
```

A model SHALL NOT be allowed to promote an uncertain claim into verified fact merely because another model agrees with it.

## 13. BIOS Conformance

For governed reasoning:

```text
Model output
    -> BIOS validation
        -> PASS
        -> REJECT / REVISE
```

Repeated non-conformance SHALL eventually escalate rather than silently accepting invalid output.

The validator MUST be able to distinguish at least:

- missing evidence;
- invalid inference;
- unsupported conclusion;
- contradiction;
- authority violation;
- scope violation;
- execution claim without evidence;
- knowledge claim without validation.

## 14. Learning

Metatron SHALL NOT depend on retraining external LLMs for institutional memory.

Workers learn operationally by producing validated knowledge artifacts.

```text
Observe
  -> validate
  -> record
  -> retrieve later
```

External LLM weights remain external implementation state.

Metatron institutional knowledge remains under Metatron governance.

## 15. Execution Boundary

Intelligence SHALL NOT create execution authority.

The execution path remains governed by the existing Metatron execution architecture.

Conceptually:

```text
Human / Worker intent
  -> reasoning
  -> decision
  -> authorization
  -> Gateway
  -> execution
  -> observation
  -> verification
  -> evidence
```

Neither Telegram, an LLM provider, nor BIOS may silently bypass the Gateway boundary.

## 16. Final Responsibility Model

```text
Workplace
  = communication / interaction

Workforce
  = institutional workers and work semantics

Knowledge Fabric
  = information and institutional memory

Tool Fabric
  = capabilities / external systems

Intelligence Fabric
  = reasoning capability

BIOS
  = constitutional governance / conformance

Gateway
  = external boundary enforcement

Execution
  = authorized action and outcome
```

## 17. Non-Goals

This proposal does NOT define:

- one LLM per Worker;
- permanent LLM conversations;
- Telegram as the institutional source of truth;
- an LLM as an authority;
- majority voting as truth determination;
- automatic promotion of model output to knowledge;
- BIOS as a prompt-only mechanism;
- a new MIL institutional domain;
- a replacement for Workplace / Communication;
- a replacement for Gateway.

## 18. Architectural Principle

The final principle is:

> **Workers do not chat with AI. Workers have access to intelligence.**

And:

> **Workers do not depend on LLMs for knowledge. They acquire knowledge through retrieval, tools, observations, and other Workers, then use intelligence to reason over that knowledge.**

Finally:

> **BIOS governs consequential cognition and action; it does not suppress ordinary human conversation.**

## 19. Acceptance Gate Before Implementation

Before this proposal becomes authoritative, it MUST be reconciled against:

1. Metatron Universal SOT;
2. Master Execution Plan;
3. current Workplace / Communication contracts;
4. Workforce SOT;
5. BIOS canonical artifacts;
6. Gateway SOT and execution boundary;
7. existing Phase 3 PASS state.

Only after reconciliation should the relevant portions be promoted into canonical SOT and implementation contracts.
