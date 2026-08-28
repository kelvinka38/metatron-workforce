# METATRON WORKPLACE — APPROVED ARCHITECTURE PROPOSAL

Status: FOUNDER APPROVED — DOCUMENTATION PHASE AUTHORIZED — IMPLEMENTATION NOT AUTHORIZED

## 1. North Star
Metatron Workplace is the persistent institutional working environment in which authorized Humans and Workers understand what requires attention, communicate, coordinate, meet, originate and perform work, make and receive decisions, report, escalate, inspect institutional reality, and preserve the context and history of institutional activity.

Short form: **WORKPLACE = WHERE THE INSTITUTION WORKS.**

Every Workplace surface must answer at least one of five questions: what needs my attention; what is being worked on; who is responsible; what changed; what evidence supports it. A feature that answers none of these and lacks canonical institutional justification is not built.

## 2. Domain boundary
- Gateway = how actors cross the institutional boundary.
- Gateway Front Office / Metatron Operator = reception, discovery, connection and transfer.
- Workplace = where authorized institutional participants work.
- Workforce = who works and Workforce operating reality.
- Execution = how authorized action becomes real.

Workplace SHALL NOT create a Front Office / Back Office / Management Office domain taxonomy. Workplace projects and coordinates institutional reality without taking semantic ownership from Workforce, Governance, Organization, Authorization, Execution, Knowledge, Economy, Observation or other authoritative domains.

## 3. Three-layer Workplace model
1. Experience Layer — Web/PWA and replaceable channel clients/adapters.
2. Coordination Layer — persistent conversations, meetings, attention/inbox state, subscriptions, working context, explicit cross-object transitions and handoff.
3. Projection Layer — authorized projections of canonical Worker, Work, Objective, Assignment, Decision, Report, Performance, Finance, Evidence and other domain state.

Projection does not transfer ownership.

## 4. Product surfaces
Workplace product surfaces are organized by institutional activity, not office taxonomy:
- Home / Attention
- Inbox
- Conversations
- Work
- Objectives
- Meetings
- Decisions & Approvals
- Reports
- Organization
- People / Workforce
- Escalations
- Performance
- Financial Views
- Management Views
- Universal Search / Command
- Activity / Institutional Journal
- Saved Views

These are linked projections/actions, not independent semantic silos.

## 5. Home / Attention
Home answers: **What deserves my attention now?**

It prioritizes attributable items using applicable authority requirement, risk, urgency, deadline, dependency, blocker, escalation, material change and explicit subscription. AI-derived urgency MUST NOT masquerade as institutional truth.

## 6. Unified Inbox
Inbox is an attention-delivery projection that may aggregate communication, mentions, assignments, approval requests, decisions, reports, escalations, meetings, notifications and watched-object changes.

`INBOX ITEM != COMMUNICATION != WORK != DECISION != AUTHORIZATION`.

Inbox items reference authoritative objects rather than duplicating their state.

## 7. Conversations
Human↔Worker, Worker↔Worker, Human↔Workers and Workers↔Workers are first-class. Conversation preserves participants, institutional context, subject, messages, references, evidence, related objects, timestamps and provenance.

Explicit transitions may include Conversation/Message → Work, Proposal, Decision Request, Escalation, Meeting or Evidence attachment. Communication never implicitly creates Work, Decision, Authority or Authorization.

## 8. Workbench and Objectives
Workplace exposes personal/team/delegated/blocked/overdue/watched/completed work and objective projections. Work and Objective detail must expose ownership, status, assignments, capacity where applicable, dependencies, risks, material changes, evidence and outcomes without inventing progress.

Workplace does not own Work/Objective semantics where canonical Workforce or another domain owns them.

## 9. Meetings
Meeting is a bounded institutional coordination context with purpose, participants, agenda, context, evidence, decisions, action items, owners, deadlines, minutes and follow-up. Gateway Operator may establish a Meeting Room through Workplace capability. Meeting outputs persist after the live interaction ends.

## 10. Decision Center
Proposal, Review, Decision, Approval, Authority, Authorization, Assignment and Execution remain distinct. Workplace provides authorized interaction and projection across their lifecycle but does not collapse or own their authoritative semantics.

## 11. Reports
Reports are attributable institutional artifacts with author/source, scope, period, generation time, evidence references, related Work/Objectives, distribution and revision. Where applicable claims distinguish Fact, Observation, Interpretation, Forecast, Recommendation and Outcome. Material claims should be drillable to supporting evidence.

## 12. Organization and People
Organization views and org charts are projections of authoritative relationships; `ORG CHART != AUTHORITY`, `REPORTING != UNLIMITED AUTHORITY`, `POSITION != WORKER`, `ROLE != AUTHORIZATION`.

Worker profiles expose applicable participation, position, reporting, capabilities, qualifications, authority references, availability/capacity, current work, performance, activity and experience while preserving persistent Worker identity independently of runtime/model.

## 13. Escalations and Management Views
Escalations expose cause, owner, origin, severity, affected scope, evidence, attempted resolution, required authority/resource, deadline, state and resolution. Founder/System Authority is the last appropriate authority, not a universal operational fallback.

Management Views may project institution overview, objectives, delivery, Workforce, capacity, staffing, performance, cost, risk, incidents, decisions and evidence. Autonomous work remains attributable and visible without unnecessary Human approval.

## 14. Universal Search / Command
Permission-aware search must resolve authorized Workers, Organizations, Objectives, Work, Assignments, Conversations, Meetings, Decisions, Reports, Escalations and Evidence. Search MUST NOT disclose unauthorized object existence or metadata.

Command actions are convenience entry points only. Intent never bypasses authorization.

## 15. Institutional Context Graph
Every material object has a context page exposing authorized relationships around it, e.g. Objective → Owner → Work → Assignment → Conversation/Meeting → Decision → Authorization → Execution → Outcome → Evidence/Report.

The Workplace context graph is a projection/navigation model, not a competing source of truth. Edges resolve to authoritative domain records.

## 16. Institutional Journal
Every material object exposes a human-readable attributable history of material changes. Institutional Journal is distinct from raw forensic/system audit logs and references supporting records/evidence.

## 17. Watch / Follow and Saved Views
Authorized users may follow material institutional objects and receive material-change signals. Saved Views are stored authorized queries/projections such as My Decisions, Gateway Blockers or Director Reports. `SAVED VIEW != NEW INSTITUTIONAL TRUTH`.

## 18. Presence, availability and continuity
Workplace may project Worker availability/presence states from authoritative sources. Runtime failure, model replacement or channel loss MUST NOT destroy Worker identity, institutional conversation, work history or accountability. Continuity may use restored runtime, delayed response or authorized handoff/delegation.

## 19. Notifications and personalization
Notifications are derived signals referencing canonical objects and may be delivered in-app or through approved replaceable channels. Personalized Home, subscriptions, saved views, recent/pinned objects and notification preferences do not create personalized institutional truth.

## 20. Stable identity and deep links
Material Workplace objects require stable navigable identity and permission-aware deep links suitable for Web/PWA and channel handoff. Direct navigation is subject to the same authorization as every other access path.

## 21. Channel independence
Web/PWA is the full-fidelity primary Workplace client. Telegram and future native/mobile/other channels are replaceable adapters/projections through applicable Gateway boundaries. `TELEGRAM != WORKPLACE`, `WEB UI != WORKPLACE`, `MOBILE APP != WORKPLACE`.

Replacing a channel must not replace institutional identity, history or state.

## 22. Degraded/offline truthfulness
Clients explicitly distinguish LIVE, STALE, OFFLINE and DEGRADED state. Stale data MUST NOT be represented as current. Privileged/risky mutations MUST NOT silently queue offline unless canonical semantics explicitly allow it.

## 23. Evidence-first UX
Material derived state should expose its basis. AI/model interpretation MUST NOT masquerade as institutional fact, retrieved current reality, authority, evidence or deterministic progress. Users must be able to answer why a material state/claim is being shown where supporting evidence exists.

## 24. Security and privacy
Workplace requires identity, authentication, authorization, scope/context isolation, session protection, provenance and auditability. UI hiding is not authorization. Authorization applies consistently to UI, API, search, deep links, notifications and activity feeds.

Internal canonical Workforce/domain APIs remain protected; Workplace exposes purpose-built application/presentation boundaries.

## 25. Mobile-first UX
Primary requirement: full authorized institutional capability from a minimally capable Internet-connected phone without local persistent state or workstation dependency.

Recommended primary navigation: Home · Inbox · Work · Meetings · More. More exposes Objectives, Decisions, Reports, Organization, People, Escalations, Performance, Financial, Management and Saved Views. Larger clients may use sidebar/context/detail layouts without changing semantics.

## 26. Non-goals
Workplace is not a Slack/Teams/Jira/Notion/Drive/ERP/CRM/email/video-conferencing/BI clone, authority engine, execution engine or AI-agent playground. Commodity capabilities should be integrated rather than rebuilt unless institutional semantics require ownership.

## 27. Non-negotiable invariants
1. Workplace is not Gateway.
2. Workplace is not Workforce.
3. Projection does not transfer domain ownership.
4. Communication does not create authority.
5. UI action does not bypass authorization.
6. Channel does not own institutional state.
7. Runtime does not define Worker identity.
8. Autonomy does not remove visibility/accountability.
9. Derived/AI interpretation does not masquerade as institutional fact.
10. Founder/System Authority must not become routine task router or operational fallback.

## 28. Production completion acceptance
Workplace is NOT COMPLETE until production evidence proves, at minimum:
- phone access, authentication, authorization, deep-link/session security;
- Human↔Worker, Worker↔Worker and group communication;
- durable conversation and channel-replacement continuity;
- explicit Conversation→Work and Meeting→Decision/action transitions;
- Proposal→Review→Decision and Escalation→appropriate-authority flows;
- Objective, Work, Assignment, schedule/capacity, blocker/recovery and evidence/outcome visibility;
- permission-aware universal search and Context Graph navigation;
- Organization/People views;
- Home attention, Management Views, Reports, Journal, Watch/Follow and Saved Views;
- application restart, runtime replacement and Worker continuity;
- evidence/provenance drill-down and explicit stale/current state;
- no unauthorized internal API exposure and consistent authorization across all access surfaces;
- one real delegated Objective reaching attributable Outcome without Founder routing routine work.

Any material category failure means **WORKPLACE NOT ACCEPTED**.

## 29. Documentation-before-implementation gate
Founder approval of this architecture authorizes documentation only, not implementation.

Required documentation set before implementation authorization:
1. Workplace SOT / canonical reconciliation
2. Workplace Architecture
3. Domain / Ownership Matrix
4. Information + Context Graph Model
5. Communication / Conversation Model
6. Meeting / Coordination Model
7. Attention / Inbox / Notification Model
8. Channel Independence Contract
9. Security + Authorization Contract
10. Cross-Domain Integration Contracts
11. UI / Experience Architecture
12. Data / State / Event Model
13. Continuity / Recovery Model
14. Acceptance Model
15. Implementation Target
16. Master Execution Plan

Sequence: `APPROVED PROPOSAL → COMPLETE DOC SET → DOC REVIEW → FOUNDER FREEZE/APPROVAL → IMPLEMENT COMPLETE → INTEGRATE COMPLETE → FORMAL TEST → FIX/RETEST → ACCEPT`.

No implementation work is authorized by this document.
