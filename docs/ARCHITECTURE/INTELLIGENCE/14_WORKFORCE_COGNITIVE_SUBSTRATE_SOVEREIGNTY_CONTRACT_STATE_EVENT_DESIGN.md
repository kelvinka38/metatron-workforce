# WORKFORCE COGNITIVE SUBSTRATE SOVEREIGNTY — CONTRACT / STATE / EVENT DESIGN

**Status:** FOUNDER-APPROVED DERIVATION  
**Implementation:** NOT STARTED

## Core contracts

`IntelligenceOriginContext` carries `originType`, `actorId`, optional Worker/Object/Assignment/Step/ExecutionAttempt references, `capabilityRef`, and `requestId`. WORKER origin requires canonical `workerId`; missing provenance fails closed and cannot default to HUMAN.

`IntelligenceComputeEndpoint` carries `endpointId`, `computeOwner`, capability set, health, capacity, model identity and transport reference. `computeOwner` is at least `METATRON_OWNED | EXTERNAL_PAID`.

`CognitionAdmissionDecision` records origin, allowed compute owners/endpoints and denial reason/evidence. Mandatory rule: `WORKER → {METATRON_OWNED}` only.

`WorkerCognitionRequest` carries actual Worker/Object/Assignment/Step/ExecutionAttempt identity plus capability, instructions, context and evidence references. Generic runtime requester strings cannot replace Worker identity.

`InferenceConsumptionRecord` records request/origin lineage, compute owner, endpoint/model, usage, latency, status, provider request reference when external, and observation time. It is evidence, not authority.

## Capacity sub-lifecycle

Cognition infrastructure may use:

```text
ADMITTED → QUEUED → RUNNING → SUCCEEDED
                     ↘ FAILED_RETRYABLE
                     ↘ FAILED_TERMINAL
                     ↘ RECONCILIATION_REQUIRED
```

This is not a second Objective or Assignment lifecycle. Queued cognition keeps owning work non-terminal unless canonical Workforce policy independently decides otherwise.

## Events

At minimum: `CognitionRequestAdmitted`, `CognitionRequestQueued`, `CognitionRequestStarted`, `CognitionRequestCompleted`, `CognitionRequestFailed`, `CognitionRequestReconciliationRequired`, `CognitionCapacityRecovered`, and `WorkerExternalInferenceDenied`. Every Worker event carries canonical lineage.

## Failure semantics

Internal conditions such as capacity wait, node/model unavailable, malformed output and timeout may queue, bounded-retry, route to another qualified METATRON_OWNED endpoint, or fail truthfully. They never authorize EXTERNAL_PAID fallback for Worker origin.

External Frontier failures must distinguish rate limit, billing exhausted, auth failure, network timeout, provider 5xx, invalid request/response and disabled. Billing/auth/disabled states are hard unavailable until explicit recovery evidence exists.

## Retrieval contract

Worker-reachable retrieval returns sources/data/evidence references and limitations. It may not read external LLM provider credentials or perform hidden paid model inference.

## Secret boundary

After cutover: Workforce and Cognition Node have no OpenAI/Gemini/Anthropic keys; Frontier Broker may have configured provider keys. Secret values never enter evidence.

## Idempotency and authority

Logical cognition request identity remains stable across retry/recovery. Model output, endpoint selection, high confidence, cognition admission or capacity ownership never creates action authority. Consequential effects still pass existing Assignment/Authorization/Execution gates. Any need for a new canonical lifecycle state triggers governed change control rather than silent invention.
