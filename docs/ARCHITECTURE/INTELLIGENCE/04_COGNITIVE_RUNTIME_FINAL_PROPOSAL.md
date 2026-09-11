# METATRON COGNITIVE RUNTIME — FINAL PROPOSAL

**Status: FOUNDER APPROVED — 2026-09-11**

## 1. Product thesis

Metatron is not a wrapper around ChatGPT, Claude, Gemini or any other model. Human/client channels and cognition providers are separate roles.

```text
ChatGPT / Claude / Gemini / Telegram / Web / API / future clients
                                ↓
                             METATRON
                                ↓
                    Intelligence / Cognitive Fabric
                                ↓
              OpenAI / Anthropic / Google / local / future
```

The runtime is provider-neutral, context-first and evidence-governed. Cognitive state belongs to Metatron, not to a provider session.

## 2. Founder locks

1. **Context Before Cognition** — governing institutional context is resolved before frontier reasoning.
2. **Cheapest Sufficient Compute** — deterministic state, retrieval, calculation, rules, DB/API and tools are preferred when sufficient.
3. **One Frontier Call by Default** — one logical request should consume at most one frontier cognition call unless a machine-recordable escalation condition exists.
4. **Escalation Requires Evidence** — a second model/call is exceptional, bounded and reason-coded.
5. **Cognitive State Belongs to Metatron** — provider conversations are never institutional memory.
6. **Reuse Valid Cognitive Work** — semantically equivalent work against the same context/evidence version should reuse a valid cognitive artifact.
7. **External Research Is Subordinate to Canonical Institutional Truth** — public evidence may extend or challenge, never silently override canonical authority.

## 3. Institutional invariants

```text
LLM != METATRON
LLM != WORKER
MODEL != ROLE
CLIENT != PROVIDER
INTELLIGENCE != AUTHORITY
KNOWLEDGE != COGNITION
INTENT != AUTHORIZATION
CLAIM != EVIDENCE
DECISION != EXECUTION
EXECUTION != OUTCOME
```

A Worker is a persistent institutional actor. It is not a provider session, a prompt, a model name or a chat window.

## 4. Target runtime

```text
Human / System / Worker
        ↓
Gateway / Channel Boundary
        ↓
Deterministic Ingress + CanonicalRequestEnvelope
        ↓
Institutional Context Resolver
        ↓
Information Requirement Engine
        ↓
Knowledge / Tool acquisition where sufficient
        ↓
Cognition Need Gate
        ↓
Intelligence Case
        ↓
Shared Intelligence Fabric
        ↓
ONE frontier call by default
        ↓
Result / Evidence Gate
        ↓
bounded evidence-backed escalation only when justified
        ↓
authorized Execution / Observation
        ↓
Settlement / reusable state / learning
```

## 5. Core components

- **Gateway / Capability Edge**: transport and externally controlled capability boundary only; it is not the brain.
- **Deterministic Ingress**: channel normalization, explicit controls, identity and continuity metadata.
- **Institutional Context Resolver**: resolves relevant authority, canonical SoT, approved architecture/spec/plan, runtime state, Case/Task/Worker state and prior evidence.
- **Information Requirement Engine**: marks information known/missing/required/optional/obtainable/unavailable/stale/conflicting and chooses the cheapest authoritative acquisition path.
- **Knowledge / Tool Fabric**: deterministic retrieval, state, APIs, DBs, repository/runtime evidence and authorized external sources.
- **Cognition Need Gate**: determines whether frontier cognition is necessary after deterministic acquisition.
- **Intelligence Case**: durable coordination state for the bounded problem.
- **Intelligence Fabric**: cognitive resource manager that selects capability/provider/budget/fallback and owns frontier consumption policy.
- **Cognitive Artifact Store**: provider-neutral reusable cognitive outputs keyed by objective fingerprint plus context/evidence version and capability.
- **Result / Evidence Gate**: validates evidence consistency, uncertainty and escalation need.
- **Execution / Observation / Settlement**: authority-controlled action, independent verification, outcome and learning.

## 6. Authority and context order

The Context Resolver resolves only relevant material and records provenance/freshness/conflicts. It must not dump all documents into every prompt.

```text
Universal / Constitution
→ Canonical SoT
→ Approved Architecture
→ Approved Specification
→ Approved Implementation Plan
→ Repository / Runtime State
→ Case / Task / Worker State
→ Prior Evidence
```

Upstream canonical authority always wins. Conflicts are surfaced explicitly rather than hidden by downstream code or prompts.

## 7. Acquisition preference

1. Case state / valid reusable artifact / cache
2. canonical Knowledge
3. institutional artifacts
4. repository/runtime state
5. deterministic API/DB/calculation
6. Worker observations/evidence
7. authorized structured external source
8. web/research
9. frontier cognition
10. Human-only information

Missing information is not automatically a reason to call a model. If Metatron can obtain it deterministically, obtain it first.

## 8. Canonical contracts

### CanonicalRequestEnvelope

```json
{
  "request_id": "...",
  "requester_ref": "...",
  "channel": "chatgpt|telegram|web|api|...",
  "raw_input": "...",
  "case_ref": "...",
  "worker_ref": "...",
  "explicit_controls": {
    "depth": null,
    "provider": null,
    "output_contract": null
  },
  "authority_context_ref": "...",
  "trace_ref": "..."
}
```

### InstitutionalContextPackage

```json
{
  "context_id": "...",
  "authority_chain": [],
  "canonical_refs": [],
  "plan_refs": [],
  "runtime_refs": [],
  "case_refs": [],
  "evidence_refs": [],
  "conflicts": [],
  "freshness": {},
  "fingerprint": "..."
}
```

### InformationRequirement

```json
{
  "requirement_id": "...",
  "question": "...",
  "importance": "required|supporting|optional",
  "status": "known|missing|stale|conflicting|unavailable",
  "preferred_source": "...",
  "acquisition_cost": {},
  "authority_required": null,
  "evidence_refs": []
}
```

### CognitionRequest

```json
{
  "case_ref": "...",
  "objective": "...",
  "capability": "...",
  "context_refs": [],
  "evidence_refs": [],
  "existing_artifact_refs": [],
  "risk": "...",
  "depth": "FAST|ANALYZE|DEEP",
  "budget": {},
  "output_contract": {},
  "authority_context_ref": "..."
}
```

### CognitiveArtifact

```json
{
  "artifact_id": "...",
  "case_ref": "...",
  "artifact_type": "...",
  "input_fingerprint": "...",
  "provider": "...",
  "model": "...",
  "result": {},
  "claims": [],
  "evidence_refs": [],
  "uncertainties": [],
  "created_at": "...",
  "valid_until": null,
  "reusable": true
}
```

### ProviderUsageRecord

```json
{
  "request_id": "...",
  "case_ref": "...",
  "provider": "...",
  "model": "...",
  "purpose": "primary|fallback|challenge|synthesis",
  "reason_code": "...",
  "input_tokens": 0,
  "output_tokens": 0,
  "latency_ms": 0,
  "estimated_cost": null,
  "success": true
}
```

Unknown pricing or usage remains null/unknown. The runtime must not invent cost.

## 9. Provider budget

- **FAST**: zero calls when deterministic; otherwise at most one initial frontier call.
- **ANALYZE**: one initial call by default; a second only for a valid escalation reason.
- **DEEP**: deeper retrieval/computation is allowed, but still one initial frontier call by default; additional cognition must be evidence-triggered.
- **Multi-model**: disabled by default. Provider count is not depth.

Valid escalation reasons:

```text
PROVIDER_FAILURE
MATERIAL_CONTRADICTION
INSUFFICIENT_EVIDENCE
HIGH_CONSEQUENCE_CHALLENGE
EXPLICIT_HUMAN_REQUEST
CAPABILITY_MISMATCH
NOVEL_INFORMATION_ACQUIRED
```

Invalid reasons include: "deep mode", "another model might be better", "always use three", or unbounded retries.

## 10. Core flows

**Deterministic**: request → context → deterministic answer/action → no frontier call.

**Ordinary cognition**: request → context/acquisition → cognition gate → one provider → evidence/result gate → answer.

**Institutional work**: request → authority/context → information requirements → cognition as needed → execution authorization → Workforce execution → Observation → settlement.

**Missing information**: acquire deterministically when possible; ask Human only when the missing semantic choice or fact is Human-only.

**Contradiction**: seek stronger evidence first; targeted challenge only if contradiction remains material.

**Cross-channel continuity**: the same Case identity survives client/channel changes.

## 11. Acceptance locks

- AC-01 Context before cognition.
- AC-02 Deterministic work can complete with zero frontier calls.
- AC-03 One initial frontier call by default.
- AC-04 Provider fallback is bounded and observable.
- AC-05 Multi-model is exceptional, reason-coded and budgeted.
- AC-06 Valid cognitive artifacts can be reused.
- AC-07 Provider-neutral contracts.
- AC-08 Channel neutrality across at least two clients.
- AC-09 Canonical Knowledge/authority precedence.
- AC-10 Stronger evidence before contradiction challenge.
- AC-11 Provider usage/budget observability.
- AC-12 No provider-owned institutional memory.
- AC-13 Authority/authorization remain outside cognition.
- AC-14 Current runtime compatibility preserved during migration.
- AC-15 Credit reduction measured against baseline; no invented percentage.
- AC-16 No regression to principal keyword/regex semantic routing.
- AC-17 Explainable trace from logical request to provider use and outcome.

## 12. Completion condition

```text
Human Objective
→ resolve governing context
→ know what information already exists
→ acquire what is missing cheaply/reliably
→ decide whether cognition is needed
→ ONE appropriate frontier call by default
→ evaluate evidence/result
→ justify any escalation
→ perform governed action
→ observe outcome
→ retain reusable institutional state
```
