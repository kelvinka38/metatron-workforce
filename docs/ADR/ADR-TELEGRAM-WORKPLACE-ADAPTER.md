# ADR — Telegram Workplace Adapter

## Status

PROPOSED — architectural decision for controlled implementation.

## Authority

This ADR is subordinate to the canonical Metatron Workforce architecture and the frozen Phase 3 Workplace / Communication contracts.

Primary references:

- `docs/PHASE_3/01_WORKPLACE_COMMUNICATION_MODEL.md`
- `docs/PHASE_3/02_CONVERSATION_MODEL.md`
- `docs/PHASE_3/05_WORKPLACE_AUTHORIZATION_BOUNDARY.md`
- `docs/PHASE_3/PHASE_3_GATE.md`
- `docs/EXECUTION_RUNTIME_INTEGRATION_SOT.md`
- `docs/MASTER_EXECUTION_CHECKLIST.md`

## Context

Metatron requires a practical Human ↔ Metatron / Worker communication surface. Telegram is selected as the initial operational interface for the single-owner MVP, while the architecture must remain capable of supporting additional users and additional interface channels later.

Phase 3 already defines and freezes the institutional Workplace / Communication semantics. That contract is transport-independent and explicitly does not prescribe UI technology, transport protocol, database schema, or vendor. Therefore Telegram must be introduced as an adapter to the existing Workplace / Communication boundary rather than as a new institutional domain or a replacement for Phase 3.

Gateway remains the external boundary enforcement mechanism. Telegram must not become a Gateway, bypass Gateway authorization, or acquire execution authority.

## Decision

Adopt Telegram as a **Workplace Communication Adapter**.

The adapter translates Telegram-originated human interactions into the existing Workplace / Communication contract and translates attributable Metatron/Worker responses back to Telegram.

Conceptually:

```text
Human
  ↓
Telegram
  ↓
Telegram Workplace Adapter
  ↓
Workplace / Communication Contract
  ↓
Metatron / Workforce / applicable Nodes
  ↓
Gateway / Execution where authorized
  ↓
Response / Evidence / Status
  ↓
Telegram Workplace Adapter
  ↓
Human
```

## Boundary Rules

1. Telegram is a transport/interface adapter, not an institutional domain.
2. Telegram does not create or grant institutional authority.
3. Telegram does not authorize execution.
4. Telegram does not replace Gateway authorization or external boundary enforcement.
5. Telegram does not own Workforce, Assignment, Authorization, Execution, Outcome, or Evidence state.
6. The existing Workplace / Communication contract remains authoritative for communication semantics.
7. Material interactions must preserve attributable identity and organizational/workspace context as required by the applicable contract.
8. A Telegram conversation must not become the institutional source of truth for domain state.
9. Telegram-specific identifiers must not replace canonical Metatron institutional identities.
10. Telegram-specific implementation details must remain replaceable without changing institutional semantics.

## Identity and Multi-User Boundary

The initial deployment may be restricted to one authorized human account.

The implementation MUST NOT hard-code the institutional model so that Telegram identity is equivalent to the Metatron user identity. Instead, the adapter must maintain an explicit mapping between the external Telegram actor and the canonical Metatron identity/context used by the Workplace contract.

This preserves a path to:

```text
Telegram User A → Metatron User / Workspace A
Telegram User B → Metatron User / Workspace B
...
```

without requiring a redesign of the Workplace contract.

Authentication, account linking, authorization, and tenant/workspace isolation remain Metatron/institutional concerns; the Telegram adapter only transports the authenticated interaction context.

## Conversation Mapping

Telegram chat/message identifiers are adapter-level identifiers.

They may be retained as external references for correlation, but canonical conversation identity remains governed by the Metatron Workplace / Conversation model.

The adapter must preserve, where applicable:

- external Telegram actor reference;
- canonical human identity reference;
- canonical conversation reference;
- message correlation;
- organizational/workspace context;
- referenced work/assignment/proposal/execution context;
- provenance/evidence references.

## Interaction Semantics

A Telegram message is an input to the Workplace / Communication boundary. It is not itself an authorization, assignment, execution request, policy decision, or institutional truth.

Where a message expresses an operational request, the applicable Metatron workflow determines its institutional effect.

Responses sent through Telegram must remain attributable and must not imply authority that was not granted by the underlying institutional workflow.

## AI / LLM Relationship

Telegram does not select, authorize, or directly control an LLM.

If the Metatron orchestration path invokes ChatGPT, Claude, Gemini, or another model, that decision occurs inside the applicable Metatron orchestration/node architecture.

The adapter only carries the Human interaction into and the resulting attributable communication out of Metatron.

## Gateway Relationship

The adapter may operate before Gateway in the interaction path, but it does not replace Gateway.

The canonical execution boundary remains governed by the existing execution integration contract:

```text
Human / External Actor
→ Gateway
→ Workforce
→ Worker
→ Assignment
→ Authorization
→ Execution Request
→ Worker Runtime
→ Execution
→ Outcome
→ Evidence / Observation
```

The Telegram adapter must not create an alternate execution path that bypasses this contract.

## Phase 3 Preservation

Phase 3 is already PASS and frozen. This decision does not reopen Phase 3.

Any implementation work must conform to the existing Workplace / Communication model and its acceptance evidence. Changes to frozen institutional semantics require the repository's controlled change process rather than incidental modification through the Telegram implementation.

## Implementation Boundary

The first implementation should be limited to:

- Telegram transport integration;
- authorized account binding;
- inbound message normalization;
- outbound message rendering;
- conversation/message correlation;
- attributable context propagation;
- operational status/error handling;
- tests proving boundary preservation.

Telegram webhook versus polling, SDK choice, deployment topology, persistence implementation, and other vendor-specific details are implementation decisions and are not architectural authority.

## Non-Goals

This ADR does not:

- create a new Metatron institutional domain;
- create a new SOT for Telegram;
- redefine Workplace / Communication semantics;
- redefine BIOS;
- redefine Gateway;
- redefine Workforce authorization;
- define LLM routing policy;
- define execution semantics;
- make Telegram mandatory as the permanent Human interface.

## Future Interfaces

Additional interfaces may be implemented against the same Workplace / Communication boundary:

```text
Telegram Adapter
Web Adapter
API Adapter
Mobile Adapter
Other approved interfaces
```

Adding another interface must not require duplicating institutional communication semantics.

## Acceptance Criteria

The implementation is acceptable only when evidence demonstrates:

- Telegram can deliver an authenticated Human interaction into the existing Workplace / Communication contract.
- Responses can be returned to the originating Human through Telegram.
- Canonical identity and conversation context are preserved.
- Telegram identifiers do not replace canonical institutional identifiers.
- Communication does not create authority.
- Communication does not bypass authorization.
- Execution cannot be triggered through a Telegram-specific bypass path.
- Gateway remains the external boundary enforcement mechanism.
- The implementation remains compatible with future multi-user isolation.
- The implementation does not require reopening the frozen Phase 3 contract.

## Consequence

Telegram becomes the first concrete Human-facing adapter for Metatron without becoming part of Metatron's institutional source-of-truth model.

The single-owner MVP can therefore be implemented immediately, while the underlying identity and communication boundaries remain suitable for future multi-user AI/workforce deployments.
