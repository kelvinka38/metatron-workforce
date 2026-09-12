# METATRON WORKFORCE — COGNITIVE SUBSTRATE SOVEREIGNTY

**Status: FOUNDER APPROVED — ARCHITECTURE AUTHORITY FOR THIS GAP CLOSURE**

**Founder approval date:** 2026-09-12

**Implementation status:** NOT STARTED — INTENTIONALLY BLOCKED PENDING SERVER-UPGRADE REVIEW

## 1. Decision

Metatron Workers SHALL NOT depend on OpenAI, Anthropic, Gemini, or any other paid external model provider for Worker-originated cognitive operation.

The hard invariant is:

```text
WORKER_ORIGINATED_PAID_EXTERNAL_INFERENCE = 0
```

while preserving:

```text
REAL_USEFUL_AUTONOMOUS_WORKER = TRUE
```

Human-facing Metatron Intelligence may continue to use external frontier providers under existing Intelligence policy. This proposal changes Worker cognitive compute ownership; it does not ban external frontier Intelligence from Metatron as a whole.

## 2. Audited current-state defect

Existing architecture already establishes:

```text
WORKER != LLM
WORKER != PROVIDER SESSION
PROVIDER IDENTITY != WORKER IDENTITY
```

However, the audited runtime currently resolves Worker cognition through the shared provider-backed Intelligence path:

```text
Worker
  ↓
WorkerIntelligenceService
  ↓
IntelligenceFabric
  ↓
external provider-backed inference
```

Therefore Metatron owns Worker identity, institutional state, Assignment, authority, execution and evidence, but does not yet own the Worker's effective general cognitive inference substrate.

Additional audited findings:

- Worker cognition can occur repeatedly inside THINK/REFLECT execution cycles.
- the current one-frontier-call rule is scoped per logical cognition request, not per Objective or Worker;
- provider clients may fan one logical call into multiple physical requests;
- Worker-reachable `WebSearchToolAdapter` can invoke Gemini outside central cognition budget/accounting;
- Founder-defined Worker `cost-limit:*` metadata is not currently an enforcement mechanism;
- existing 1,000-Worker tests prove representation/capacity semantics, not cognitive-cost sovereignty;
- `GeneralCognitiveWorkerBrain` uses generic `worker-cognitive-runtime` requester attribution on the audited path instead of the actual allocated Worker;
- the current Workforce production container possesses OpenAI, Gemini and Anthropic API credentials.

## 3. Scope name

This approved gap closure is named:

```text
WORKFORCE COGNITIVE SUBSTRATE SOVEREIGNTY
```

It does not reopen the accepted Worker Autonomy closure. Existing autonomy evidence remains historically valid for identity, persistence, allocation, authority, execution, recovery, observation and evidence-backed completion.

This closure establishes a separate property:

```text
cognitive compute ownership
provider-cost independence
Worker inference sovereignty
```

## 4. Hard invariants

After closure:

```text
WORKER != LLM
WORKER != PROVIDER
WORKER COUNT != MODEL INSTANCE COUNT
WORKER COUNT != EXTERNAL API ACCOUNT COUNT
WORKER COGNITION != PAID EXTERNAL PROVIDER DEPENDENCY
WORKER_ORIGINATED_EXTERNAL_LLM_REQUESTS = 0
WORKER_ORIGINATED_EXTERNAL_LLM_TOKENS = 0
WORKER_ORIGINATED_EXTERNAL_LLM_COST = 0
```

The invariant does not weaken as Worker population grows.

## 5. Human Intelligence versus Worker cognition

Permitted by existing Intelligence policy:

```text
HUMAN → METATRON INTELLIGENCE → external frontier provider
```

Forbidden after this closure:

```text
WORKER → paid external frontier provider
```

A Worker may recommend Human-authorized frontier review. That recommendation does not authorize the Worker to consume paid external cognition itself.

## 6. One Intelligence Fabric

Metatron SHALL NOT create a parallel `WorkerAIEngine`, `LocalWorkerFabric`, `WorkerLLMFabric`, or equivalent competing policy plane.

The shared Intelligence Fabric remains canonical and gains first-class cognition-origin and compute-ownership semantics:

```text
                    INTELLIGENCE FABRIC
                           │
                  COGNITION ADMISSION
                           │
            ┌──────────────┴──────────────┐
            │                             │
       WORKER ORIGIN                  HUMAN ORIGIN
            │                             │
            ▼                             ▼
     METATRON-OWNED                approved Intelligence
         COMPUTE                        policy
                                          │
                               ┌──────────┴──────────┐
                               │                     │
                       Metatron-owned          external frontier
```

## 7. First-class origin and compute ownership

Every Intelligence invocation SHALL carry typed institutional provenance sufficient to identify at least:

```text
origin_type
actor_id
worker_id
objective_id
assignment_id
step_id
execution_attempt_id
capability_ref
request_id
```

Inference resources SHALL be classified at minimum as:

```text
METATRON_OWNED
EXTERNAL_PAID
```

Canonical admission:

```text
origin = WORKER
→ eligible_compute_owner = METATRON_OWNED only
```

No retry, fallback, deep mode, provider failure, multi-model mode or tool path may widen that set.

## 8. Admission ordering

Sovereignty admission SHALL execute before any Worker-reachable provider/model or hidden cognition path, including retrieval enrichment that can invoke a model.

Target order:

```text
Intelligence request
→ resolve institutional origin
→ CognitionAdmissionPolicy
→ determine eligible compute ownership
→ deterministic / artifact reuse
→ evidence retrieval if required
→ planner
→ eligible inference substrate
```

## 9. Physical secret separation

Application policy alone is insufficient.

After closure, the Workforce runtime SHALL NOT possess:

```text
OPENAI_API_KEY
GEMINI_API_KEY
ANTHROPIC_API_KEY
```

External provider credentials SHALL reside only behind an external Frontier transport boundary. The Metatron Cognition Node SHALL receive no paid frontier-provider API credentials. The execution sandbox SHALL continue to receive none.

## 10. Target physical topology

```text
                     HUMAN CHANNELS
                          │
                          ▼
               ┌──────────────────────┐
               │ METATRON WORKFORCE   │
               │ Worker Actors        │
               │ Intelligence Fabric  │
               │ Action / Tool Fabric │
               │ institutional state  │
               │ NO provider keys     │
               └───────┬───────┬──────┘
                       │       │
            Worker     │       │ Human-authorized
            cognition  │       │ frontier cognition
                       ▼       ▼
            ┌─────────────┐  ┌────────────────┐
            │ COGNITION   │  │ FRONTIER       │
            │ NODE        │  │ BROKER         │
            │ open-weight │  │ provider keys  │
            │ GPU runtime │  │ failure state  │
            └─────────────┘  └───────┬────────┘
                                     │
                             external providers
```

The Cognition Node and Frontier Broker are infrastructure components under the existing Intelligence architecture, not new canonical Intelligence domains.

## 11. Metatron Cognition Node

The Cognition Node provides private provider-neutral inference capacity. It owns model serving, inference execution, batching, capacity management, runtime health and internal usage telemetry.

It does not own Worker identity, Assignment, authority, execution truth, evidence truth or institutional lifecycle.

Metatron SHALL qualify strong open-weight models rather than train a foundation model as part of this closure. Multiple Metatron-owned models MAY be selected by capability when benchmark evidence justifies it.

## 12. Shared inference infrastructure

Metatron SHALL NOT instantiate one model per Worker.

```text
Worker A ─┐
Worker B ─┤
Worker C ─┤
 ...      ├── shared bounded Cognition Capacity
Worker N ─┘
```

Worker population and active inference concurrency are separate dimensions.

## 13. Deterministic work and Cognitive Artifacts remain first

Preferred order:

```text
Deterministic/action path sufficient? → no inference
Reusable valid Cognitive Artifact?    → no inference
Otherwise                             → Metatron-owned cognition
```

The closure does not force every work step through a local model.

## 14. Worker cognitive loop

The existing governed THINK → ACT → OBSERVE → REFLECT loop remains valid. Cognition may occur more than once when genuinely necessary.

The new guarantee is that every Worker-originated inference turn resolves only to Metatron-owned compute.

## 15. Worker cognition attribution

Generic `worker-cognitive-runtime` ownership is not acceptable for an actual allocated Worker's cognitive turn.

Required invariant:

```text
COGNITIVE_TURN_OWNER = ACTUAL_ALLOCATED_WORKER
```

Worker, Objective, Assignment, Step and ExecutionAttempt lineage SHALL survive through cognition, action, observation and evidence.

## 16. Web research separation

External information is not external cognition.

Worker research target:

```text
Worker
→ governed retrieval/search
→ web / API / authoritative source
→ raw attributable evidence
→ Metatron-owned cognition
→ analysis
```

Worker-reachable search tooling SHALL NOT invoke paid Gemini/OpenAI/Anthropic inference. Direct provider-secret reads inside Worker-reachable search tooling are prohibited after closure.

## 17. Frontier Broker responsibilities

The external Frontier boundary SHALL own provider credentials, provider-specific HTTP, failure taxonomy, billing/auth circuit state, physical request accounting and external-provider telemetry.

It SHALL reject Worker-originated paid-inference requests as a second independent enforcement layer.

## 18. Internal cognition capacity

Finite internal compute produces queueing, not paid-provider fallback:

```text
Worker cognition request
→ capacity available? YES → execute
                     NO  → queue / wait / reconcile
```

Internal cognitive saturation is not automatically Objective failure. Detailed lifecycle design SHALL derive a non-terminal capacity condition without inventing a competing Objective/Assignment lifecycle.

## 19. Build-to-use requirement

Closure is not achieved because a local model returns HTTP 200.

A qualified substrate must make real Workers useful:

```text
ZERO PROVIDER BILL + USELESS WORKER = FAILURE
```

The mandatory first golden workload SHALL reuse the real General Engineering GO1 shape: unknown repository defect → inspect/search/read → root cause → mutation → tests → recovery where required → Git commit → reviewable unmerged PR → independent verification → truthful Objective completion.

The sovereign GO1 lane SHALL run with external provider keys absent from the Workforce runtime.

## 20. Qualification corpus and quality gate

A frozen version-controlled `METATRON_COGNITIVE_QUALIFICATION_V1` SHALL cover at minimum:

- general engineering;
- repository audit;
- research/evidence;
- operations/root cause;
- failure recovery;
- business/analysis;
- creative role work;
- governed delegation/escalation.

Thresholds SHALL be frozen before candidate results are known. Initial target:

```text
overall objective completion >= 85%
each critical workload category >= 80%
```

Zero-tolerance gates:

```text
authority violations = 0
false external-effect claims = 0
fabricated evidence = 0
Worker→EXTERNAL_PAID inference = 0
```

Critical golden tests such as sovereign GO1 SHALL individually PASS regardless of aggregate score.

## 21. Model selection rule

Selection priority:

1. real Objective completion;
2. governed action correctness;
3. recovery capability;
4. evidence integrity;
5. structured-output reliability;
6. completion truthfulness;
7. latency/throughput;
8. infrastructure economics.

Generic public leaderboard score is secondary. Frontier parity is not required; useful autonomous Worker capability is.

## 22. Scale requirement

The existing 1,000-Worker arithmetic/representation tests are not sufficient for this closure.

New scale acceptance SHALL represent/restore at least 1,000 canonical Worker actors, enqueue cognition-bearing work, maintain bounded internal cognition concurrency, preserve independent actor state, queue excess work, recover interruptions and drain eligible work.

The number 1,000 is a test fixture, not a product ceiling.

Throughout the scale test:

```text
OpenAI Worker requests    = 0
Gemini Worker requests    = 0
Anthropic Worker requests = 0
Worker external tokens    = 0
Worker external cost      = 0
```

## 23. Inference ledger and independent network proof

Every inference execution SHALL produce an institutional usage record sufficient to attribute origin, Worker/Objective/Assignment/Step, compute owner, endpoint/model, usage, latency and status.

The invariant query:

```text
origin_type = WORKER
AND compute_owner = EXTERNAL_PAID
```

must return zero records.

Production acceptance SHALL also independently verify outbound traffic so that the same application code enforcing the rule is not the only evidence that the rule held.

## 24. Failure injection

Before closure, acceptance SHALL inject at minimum:

- Cognition Node unavailable;
- internal cognition capacity saturated;
- malformed model output;
- Cognition Node restart;
- Worker execution restart;
- all external provider accounts unavailable;
- retrieval/search unavailable.

Expected Worker behavior is bounded recovery or truthful queue/block state with state preservation and no paid external Worker fallback.

## 25. Human and Worker acceptance separation

Two proofs are required:

### Sovereign Worker proof
A canonical Objective enters after the Human semantic boundary. External Frontier may be disabled. Worker still completes using Metatron-owned cognition.

### Full Human-to-Worker proof
Human semantic interpretation may use approved external frontier capacity, then the created Objective is executed by a sovereign Worker. Ledger attribution must show Human external usage separately while Worker external usage remains zero.

## 26. Migration safety

Do not break current Worker capability before the sovereign lane passes qualification.

Before cutover, the old production path remains current while the new lane is qualified in isolation. After approved production cutover, Worker→paid-external inference becomes a policy violation and rollback SHALL NOT silently re-enable it.

## 27. Infrastructure boundary

The current small Metatron control-plane host is not approved as the production inference host by this proposal. Cognition infrastructure SHALL be independently sized from measured cognitive throughput, context, latency, concurrency, queue depth, GPU memory and task-completion evidence.

No server purchase/upgrade is authorized by this document alone.

## 28. Production acceptance summary

Closure requires simultaneous PASS for:

```text
Worker identity
actual Worker cognition attribution
Metatron-owned inference
sovereign General Engineering GO1
research/evidence workflow
failure recovery
Cognition Node restart
capacity saturation
external providers unavailable
provider credentials absent from Workforce
Worker external inference requests = 0
Worker external inference tokens = 0
Worker external LLM cost = 0
1,000+ actor cognitive scale
independent outbound-network proof
independent Observation
```

Any critical failure means `COGNITIVE SUBSTRATE SOVEREIGNTY = NOT CLOSED`.

## 29. Governance order

```text
UPSTREAM CANONICAL SOT / POLICY
→ THIS FOUNDER-APPROVED FINAL PROPOSAL
→ DETAILED ARCHITECTURE
→ CONTRACT / STATE / EVENT DESIGN
→ DETAILED IMPLEMENTATION PLAN
→ EXECUTION PLAN
→ IMPLEMENTATION
→ ACCEPTANCE
→ PRODUCTION VERIFICATION
```

Historical closure records remain intact and SHALL NOT be rewritten to pretend this requirement was already proven.

## 30. Explicit implementation hold

Founder instruction on 2026-09-12:

```text
DOCUMENTATION UPDATE = AUTHORIZED
CODE IMPLEMENTATION = NOT AUTHORIZED YET
DEPLOYMENT = NOT AUTHORIZED
SERVER-UPGRADE REVIEW = REQUIRED BEFORE IMPLEMENTATION START
```

No code or runtime implementation may begin under this proposal until the Founder completes the server-upgrade review and explicitly releases this hold.

## 31. Final definition of done

The program is complete only when real Metatron Workers independently complete real governed Objectives using Metatron-owned cognitive inference, with correct Worker attribution, governed Tools, evidence-backed completion, recoverable capacity management, and independently measured paid external LLM consumption attributable to Workers equal to exactly zero.

```text
Metatron owns the Worker
AND
Metatron owns the Worker's cognitive compute path.

WORKER SCALE != PROVIDER BILL SCALE
```
