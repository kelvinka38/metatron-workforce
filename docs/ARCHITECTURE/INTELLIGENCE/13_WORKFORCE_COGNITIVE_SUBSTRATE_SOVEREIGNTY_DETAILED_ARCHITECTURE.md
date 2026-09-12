# WORKFORCE COGNITIVE SUBSTRATE SOVEREIGNTY — DETAILED ARCHITECTURE

**Status:** FOUNDER-APPROVED DERIVATION BASELINE  
**Implementation:** NOT STARTED — PRE-IMPLEMENTATION HOLD ACTIVE

## Objective

Implement the approved invariant without creating a second Intelligence domain:

```text
WORKER origin → METATRON_OWNED cognition only
HUMAN origin  → existing Intelligence policy, including permitted external frontier
```

## Domain ownership

Workforce continues to own Worker identity, Objective/Assignment lifecycle and actor state. Intelligence Fabric owns cognition coordination. Execution/Action Fabric owns governed effects. Observation/Evidence owners remain authoritative for completion truth. The Cognition Node owns inference execution only. The Frontier Broker owns external provider transport/secrets only.

## Typed provenance

Every Intelligence request must carry authoritative runtime provenance at least for `originType`, `actorId`, `workerId`, `objectiveId`, `assignmentId`, `stepId`, `executionAttemptId`, `capabilityRef`, and `requestId`. `originType=WORKER` requires canonical Worker identity and cannot be inferred from prompt text.

## Compute ownership

Every inference endpoint is classified as:

```text
METATRON_OWNED
EXTERNAL_PAID
```

Admission is fail-closed:

```text
origin=WORKER → allowed compute owners={METATRON_OWNED}
```

Fallback, retry, deep reasoning, provider failure, multi-model mode, or tool invocation may not widen that set.

## Required order

```text
request
→ authoritative OriginContext
→ CognitionAdmissionPolicy
→ eligible compute classes/endpoints
→ deterministic/Cognitive Artifact reuse
→ retrieval if needed
→ planner
→ eligible inference endpoint
→ result/evidence governance
```

Admission must run before Worker-reachable web enrichment because retrieval tooling must not hide external paid cognition.

## Worker path

```text
Canonical Worker Actor
→ WorkerIntelligenceService
→ ActorScoped/Deliberating decorators
→ IntelligenceFabric
→ origin/compute admission
→ MetatronCognitionClient
→ private Cognition Node
```

`GeneralCognitiveWorkerBrain` must stop attributing real Worker cognition to generic `worker-cognitive-runtime`; the actual allocated Worker owns the turn.

## Physical separation

After cutover the Workforce runtime must not possess `OPENAI_API_KEY`, `GEMINI_API_KEY`, or `ANTHROPIC_API_KEY`. The Cognition Node receives no external frontier keys. External provider credentials live only behind the Frontier Broker. Execution sandboxes continue to receive none.

## Research

External information is not external cognition. Worker research becomes retrieval/search/API/direct-source evidence acquisition followed by Metatron-owned synthesis. Worker-reachable search must not call Gemini/OpenAI/Anthropic inference.

## Capacity

Worker population is independent from inference concurrency. Internal cognition is shared and bounded. Saturation queues/waits/reconciles; it never authorizes paid external fallback. Queueing preserves Worker/Objective/Assignment/Step lineage.

## Inference ledger

Every inference execution records origin, Worker/Object/Assignment/Step lineage, compute owner, endpoint/model, token usage where available, latency, status and timestamp. The invariant query `originType=WORKER AND computeOwner=EXTERNAL_PAID` must return zero.

## Scaling and compatibility

The current small control-plane host is not approved as the production inference host by this architecture. Cognition infrastructure is sized from measured cognitive throughput, context, concurrency, queue and GPU evidence after qualification. Existing deterministic paths, Cognitive Artifact reuse, Intelligence Fabric, Worker actors and governance boundaries remain canonical.
