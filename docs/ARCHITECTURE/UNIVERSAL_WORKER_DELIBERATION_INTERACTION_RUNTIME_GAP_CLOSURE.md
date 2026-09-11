# Universal Worker Deliberation & Interaction Runtime Gap Closure

Status: CANONICAL — CLOSED FOR CURRENT FOUNDER-APPROVED SCOPE
Owner: Metatron Workforce
Approval: Founder-approved 2026-09-11

## 1. Problem

Metatron already had durable Worker actors, Worker identity/state, objective/autonomy substrate, provider-neutral Intelligence, execution, Observation and routing feedback. A live Worker conversation could nevertheless collapse to:

`Human message -> worker cognition -> one model response`

That made complex underspecified Objectives behave like one-shot prompt completion even though the surrounding Worker architecture was institutional and persistent.

This closure adds a role-independent work-interaction control loop for every current and future Worker. Composer was the observed failing example, not the target architecture.

## 2. Locked invariants

- `ROLE != COGNITIVE LOOP`
- `MESSAGE != OBJECTIVE`
- `MODEL OUTPUT != COMPLETION`
- `DRAFT != FINAL`
- `WORKER IDENTITY != MODEL SESSION`
- `MISSING FIELD != AUTOMATIC CLARIFICATION`
- `AUTONOMY != GUESS EVERYTHING`
- No second Intelligence/provider/router/execution stack is permitted.

## 3. Canonical architecture

`WorkerActorRuntime`
-> `DeliberatingWorkerIntelligenceService`
-> existing `WorkerIntelligenceService`
-> existing `InstitutionalIntelligenceRuntime / IntelligenceFabric`
-> existing governed execution / evidence / Observation.

The deliberation layer owns only interaction/work progression. Existing constitutional authority, institutional grounding, provider routing, execution authorization and Observation remain authoritative.

## 4. Universal state

`WorkerDeliberationState` durably records:

- Worker ID
- active objective summary
- work stage
- next move
- interaction intent
- context sufficiency
- assumptions
- open questions
- decisions
- clarification/revision counters
- update time

The default production store is `/var/lib/metatron-workforce/worker-deliberation.json`, configurable by `METATRON_WORKER_DELIBERATION_PATH`.

State belongs to Metatron and survives provider session changes and process restart.

## 5. Work stages

The universal stage vocabulary includes:

`IDLE, UNDERSTANDING, CONTEXTUALIZING, DISCUSSING, CLARIFYING, PROPOSING, PLANNING, EXECUTING, INSPECTING, CRITIQUING, REVISING, VERIFYING, READY_TO_DELIVER, DELIVERED, WAITING_FOR_HUMAN, BLOCKED, ESCALATED, PAUSED, CANCELLED`.

Roles may add domain workflows and quality criteria above this vocabulary but may not replace the control loop.

## 6. Universal next moves

`CONVERSE, CLARIFY, PROPOSE, PLAN, ACT, INSPECT, CRITIQUE, REVISE, VERIFY, DELIVER, ESCALATE`.

The runtime uses deterministic, provider-independent pre-routing policy to prevent obvious one-shot collapse and to keep cheap/simple work direct. Provider cognition then executes under the chosen move using the existing Intelligence Fabric.

## 7. Behavior

### Complex underspecified Objective

The Worker must not jump directly to a final deliverable. It enters CLARIFY/PROPOSE behavior, asks only high-value outcome-shaping questions, or presents materially different directions and a recommendation.

### Detailed complex Objective

The Worker may proceed autonomously. The PLAN directive requires proportional planning, draft/work creation, criteria-based critique, revision of material defects and verification before final presentation.

### Simple direct work

Rewrite/translate/summarize/proofread style work remains direct. The runtime explicitly prevents clarification ceremony where context is already sufficient.

### Follow-up feedback

If the Worker is waiting on an active Objective, the next Human response is treated as feedback on the same work rather than a new unrelated prompt.

### Normal conversation

Ordinary questions/discussion do not create a false active Objective or leave the Worker stuck waiting for feedback.

### Interruptions

Pause/cancel intent is recognized in the universal control vocabulary without inventing new execution authority.

## 8. Language support

The interaction classifier folds Unicode accents before policy matching. Vietnamese commands such as `Viết cho tao một bài nhạc` and simple direct requests such as `Viết lại câu này...` are covered by acceptance tests.

## 9. Cross-channel ownership

The integration point is the canonical Worker intelligence boundary used by live Worker conversation, not a UI-specific hook. Therefore authorized Workplace, Telegram, Meeting and future channels that use the canonical Worker conversation path inherit the same behavior.

Channel adapters do not own deliberation state.

## 10. Worker-to-Worker boundary

Existing `WorkerActorRuntime.delegate(...)` remains the durable Worker-to-Worker routing envelope and explicitly does not grant authority. When delegated work reaches Worker cognition through the canonical Worker path, the same universal deliberation runtime applies. This closure does not create a parallel delegation or authorization system.

## 11. Workplace observability

Authenticated read-only endpoint:

`GET /workplace/api/workers/{workerId}/deliberation`

returns the current `WorkerDeliberationState` so Control Room/UI can render current stage, objective, next move, open questions, assumptions and decisions without scraping model text.

## 12. Acceptance mapping

- AC-D01 ambiguous complex Objective: PASS
- AC-D02 low-impact/simple direct work avoids unnecessary clarification: PASS
- AC-D03 high-value clarification directive: PASS
- AC-D04 existing conversation/memory remains upstream context and is explicitly protected from repeated questioning: PASS
- AC-D05 objective continuity across Human follow-up: PASS
- AC-D06 draft/final distinction is a mandatory runtime invariant: PASS
- AC-D07 critique/revision required for complex planned deliverable: PASS
- AC-D08 verification before final presentation required by PLAN/ACT directive: PASS
- AC-D09 arbitrary future role inherits without role-specific branch: PASS
- AC-D10 provider-independent state/control boundary: PASS
- AC-D11 restart persistence: PASS
- AC-D12 Human-required missing context produces clarification rather than silent finalization: PASS
- AC-D13 sufficiently specified work proceeds without unnecessary Human interruption: PASS
- AC-D14 pause/cancel interaction vocabulary: PASS
- AC-D15 Worker-to-Worker remains on canonical delegation + Worker cognition substrate; no parallel stack: PASS
- AC-D16 external completion claims remain subject to existing execution/evidence guard: PASS
- AC-D17 complex underspecified Objective cannot collapse directly to final delivery: PASS

Primary acceptance suites:

- `UniversalWorkerDeliberationRuntimeAcceptanceTest`
- `VietnameseWorkerDeliberationAcceptanceTest`

## 13. Implementation phases closure

The Founder-approved execution breakdown P0-P12 maps as follows:

- P0 baseline/failing behavior -> encoded as anti-one-shot acceptance.
- P1 state model -> durable Worker deliberation state/stages.
- P2 interaction interpretation -> universal objective/question/feedback/interruption policy.
- P3 context sufficiency -> SUFFICIENT/PARTIAL/INSUFFICIENT gate with simple-vs-complex discrimination.
- P4 next-move engine -> universal next-move vocabulary and policy.
- P5 work lifecycle integration -> inserted inside canonical actor-scoped Worker cognition path.
- P6 draft/critique/revision/verification -> mandatory complex-work directive.
- P7 continuity -> persisted state, follow-up continuity, provider independence, restart acceptance.
- P8 Worker-to-Worker -> reuse canonical actor delegation and same downstream Worker cognition path.
- P9 role-profile integration -> role grounding/constitution remain upstream inputs; no role-specific control branches.
- P10 UX/API exposure -> authenticated deliberation state endpoint.
- P11 generic conformance -> arbitrary-role + Vietnamese + direct/non-conversation boundary tests.
- P12 production rollout -> CLOSED by the release evidence below.

## 14. Non-goals

This closure does not train a model, build Composer-specific intelligence, replace Intelligence Fabric, replace Worker Actor Runtime, expose chain-of-thought, or bypass Authorization/Gateway/Execution/Observation.

## 15. Release evidence

Implementation release:

- PR #336: merged after CI PASS.
- Merge SHA: `96cf7fe393a656be006544ede18bb05f48e2866d`.
- Full clean Gradle build on the integrated head: PASS.
- GitHub CI: PASS.
- General Runtime Polyglot Acceptance: PASS.
- Typed Work Ingress Acceptance: PASS.
- Highway Conformance CI: PASS.
- Canonical exact-SHA production verification for `96cf7fe393a656be006544ede18bb05f48e2866d`: PASS.
- Workforce liveness/readiness: UP.
- Gateway v2 health: OK.
- Production identity verified with local `main`, running commit and immutable image all at `96cf7fe393a656be006544ede18bb05f48e2866d` at the implementation release.

A first post-merge local build attempt was invalidated by concurrent build-directory cleanup from another lane and produced broad `NoClassDefFoundError`/missing test-output artifacts. A subsequent uncontended clean build passed. That transient shared-build-directory race is not treated as product evidence and did not bypass any release gate.

## 16. Final closure decision

```text
UNIVERSAL WORKER DELIBERATION RUNTIME   CLOSED
ROLE-INDEPENDENT CONTROL LOOP           CLOSED
PERSISTENT DELIBERATION STATE           CLOSED
ANTI-ONE-SHOT COMPLEX OBJECTIVE GATE    CLOSED
FOLLOW-UP OBJECTIVE CONTINUITY           CLOSED
CROSS-CHANNEL CANONICAL INTEGRATION      CLOSED
GENERIC ROLE INHERITANCE                 CLOSED
PRODUCTION EXACT-SHA VERIFICATION        PASS
NEW ROLE-SPECIFIC COGNITIVE STACK        NONE
```

This closure is canonical for the current Founder-approved scope. Future behavior changes must be driven by observed Worker defects or newly approved scope, not by adding role-specific control loops.
