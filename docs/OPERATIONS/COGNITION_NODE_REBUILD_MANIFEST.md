# Metatron Cognition Node — Rebuild Manifest

Status: OPERATIONAL REBUILD RECORD
Purpose: preserve the information required to rebuild the separately hosted Metatron-owned Worker cognition node after the 2026-09 pilot node is destroyed.

## 1. Scope

The Cognition Node is inference infrastructure only. It does not own Worker identity, Objective/Assignment lifecycle, execution authority, repository credentials, production-host authority, or external frontier-provider credentials.

Canonical flow:

Workforce Worker origin -> IntelligenceFabric -> CognitionAdmissionPolicy -> METATRON_OWNED -> HttpMetatronCognitionClient -> private Cognition Node -> local Ollama -> open-weight model.

The Workforce control plane must remain usable when the Cognition Node is absent. Worker-origin cognition must fail closed; it must not silently fall back to paid external providers.

## 2. Pilot node facts worth preserving

Observed pilot identity:
- Hetzner server name: `metatron-cognition-test-01`
- pilot address: `5.223.43.92`
- Cognition API: `http://5.223.43.92:8091/v1/cognition`
- endpoint identity returned to Workforce: `metatron-cognition-node-ccx33`
- backend: local Ollama on `127.0.0.1:11434`
- qualified pilot model: `qwen2.5:7b-instruct-q4_K_M`
- compute ownership: `METATRON_OWNED`
- external frontier credentials on cognition node: `false`
- pilot machine class used during qualification: CCX33 / 8 dedicated AMD EPYC Milan vCPU / approximately 31 GB RAM

The old IP is not an architectural identity and MUST NOT be reused as a canonical dependency. A replacement node may use a different address.

## 3. Workforce HTTP contract

Workforce calls `POST /v1/cognition` with `Content-Type: application/json` and optional Bearer auth.

Request fields sent by `HttpMetatronCognitionClient`:
- `requestId`
- `capability`
- `objective`
- `context`
- `evidenceReferences`
- `requiredOutput`
- `provenance.originType`
- `provenance.actorId`
- `provenance.workerId`
- `provenance.objectiveId`
- `provenance.assignmentId`
- `provenance.stepId`
- `provenance.executionAttemptId`

Successful response contract:
- `result` or `text`: non-empty inference text
- `endpointId`: stable node identity
- `modelIdentity` or `model`: model identity
- `requestReference` or `requestId`: node request reference
- optional `usage.inputTokens`
- optional `usage.outputTokens`

Non-2xx is failure. Worker-origin cognition MUST NOT route to an external paid provider as fallback.

## 4. Security contract

Replacement node requirements:
- generate a NEW random Bearer secret; do not reuse the destroyed pilot token;
- keep Ollama bound to loopback (`127.0.0.1:11434`) unless a later architecture explicitly changes it;
- expose only the Cognition API required by Workforce;
- restrict network access to the Metatron control-plane path wherever practical;
- no `OPENAI_API_KEY`, `GEMINI_API_KEY`, `ANTHROPIC_API_KEY`, or equivalent frontier credentials on the node;
- do not return secrets in API output, logs, evidence, or model context;
- fail closed on bad/missing auth;
- log bounded request identity/provenance, model identity, latency, and status without logging secret values.

## 5. Rebuild sequence

1. Provision a separate inference host; do not colocate production inference on the small Metatron control-plane host by default.
2. Install Ollama as a local service.
3. Pull `qwen2.5:7b-instruct-q4_K_M` or the subsequently approved qualified replacement model.
4. Verify the model locally through Ollama before exposing the Cognition API.
5. Deploy a small API wrapper on port `8091` implementing the contract in section 3.
6. Configure endpoint identity, e.g. `metatron-cognition-node-<machine-class>`.
7. Configure a NEW Bearer secret on both the node and Workforce.
8. Verify unauthenticated `POST /v1/cognition` is denied.
9. Verify authenticated cognition returns non-empty text, endpoint identity, model identity, and request reference.
10. In Workforce set:
   - `METATRON_COGNITION_ENABLED=true`
   - `METATRON_COGNITION_URL=http://<replacement-node>:8091`
   - `METATRON_COGNITION_AUTH=<new-secret>`
   - bounded concurrency/queue values appropriate to measured capacity.
11. Recreate/deploy Workforce through canonical immutable-SHA Highway.
12. Run one real production acceptance proving a single lineage:
   Human intent -> durable Objective -> assigned Worker -> METATRON_OWNED cognition evidence -> real bounded action -> verified observation -> Objective COMPLETED.
13. Accept the node only when durable evidence includes `metatron-cognition-endpoint:*`, `metatron-cognition-model:*`, `metatron-cognition-request:*`, actual action evidence, and terminal completion for the same Objective/Worker lineage.

## 6. Current shutdown posture

Workforce now has an explicit kill switch: `METATRON_COGNITION_ENABLED`.

Default is `false`. When false, a configured/stale `METATRON_COGNITION_URL` or auth value does not instantiate the cognition client. Worker-origin cognition therefore fails immediately with the canonical unavailable state instead of waiting on the destroyed pilot IP.

Re-enabling cognition requires an explicit `METATRON_COGNITION_ENABLED=true` plus a valid replacement URL/auth configuration and a controlled Workforce recreate/deploy.

## 7. What is and is not preserved

Preserved here:
- architecture boundary;
- HTTP request/response contract;
- pilot model/backend identities;
- pilot machine class and endpoint identity;
- security constraints;
- Workforce reconnection procedure;
- production acceptance criteria.

Not preserved from the disposable pilot node:
- the exact ad-hoc node-side wrapper implementation/service unit used during the pilot;
- model cache files;
- transient Ollama runtime data;
- the old Bearer secret.

Those are intentionally treated as replaceable infrastructure. A replacement wrapper must implement the canonical contract above and be qualified again before production use.
