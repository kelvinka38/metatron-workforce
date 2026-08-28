# METATRON WORKPLACE — FULL PRODUCT ARCHITECTURE

Status: IMPLEMENTATION CONTRACT (derived from canonical Workforce/Workplace SOT; not competing SOT)

## Product thesis
Workplace is the institutional place where recognized Humans and Workers work together. Workforce remains WHO WORKS; Workplace is WHERE THEY WORK; Work is WHAT THEY ARE DOING; Execution is HOW real action is performed.

The native Web/PWA is the full-fidelity primary surface. Telegram and future channels are adapters/projections. Institutional continuity must not depend on a channel, browser session, runtime or intelligence provider.

## Information architecture

### 1. FRONT OFFICE — daily institutional work
Front Office is the default customer-facing workspace.

- **Home / Today** — priorities, assigned work, objective progress, alerts, upcoming meetings, unread communication, decisions requiring attention.
- **Inbox** — Human↔Worker and Worker↔Worker conversations; notifications; explicit Conversation→Work transition.
- **Work** — personal/team queue, assignments, status, owner, objective, schedule, authorization state, evidence/outcome links.
- **Objectives** — objective ownership, decomposition, progress, blockers, linked assignments/work/outcomes.
- **Meetings** — agenda, participants, minutes, decisions, actions and provenance.
- **Decisions & Approvals** — proposals, reviews, approvals/rejections/revisions/escalations; approval is risk/policy driven, not universal.
- **Reports** — operational reports, facts/observations/forecasts/interpretations/recommendations, upward reporting.
- **People / Workforce** — Worker directory, participation, position/role, capability, qualification, availability/capacity, current load.
- **Notifications** — actionable institutional events, not a second source of truth.
- **Escalations** — blockers, exceptions, authority/budget/capacity thresholds and resolution state.

### 2. MANAGEMENT OFFICE — management control plane
- Portfolio / Objectives
- Workforce & capacity
- Staffing gaps
- Delivery / blocked / overdue
- Performance & outcomes
- Cost/resource evidence projections
- Risk / approvals / escalations
- Management activity and evidence

### 3. BACK OFFICE — administration and institutional configuration
- Participants / Worker admission and lifecycle
- Participation / positions / roles
- Capability & qualification attestations
- Schedules / availability / capacity policies
- Workplace/channel adapters
- Runtime/provider bindings (references only)
- Authority / authorization references and policy projections (not authority ownership)
- Audit/evidence/provenance views
- Environment/revision/system health

## Navigation
Mobile-first bottom navigation: **Home · Inbox · Work · People · More**.
Desktop adds persistent left navigation and separates Front Office, Management Office and Back Office.

## Interaction laws
1. Every material item has a stable institutional ID and provenance.
2. Conversation is not authorization or execution.
3. Conversation→Work, Proposal→Decision, Decision→Assignment/Authorization, Work→Execution Request are explicit transitions.
4. Human→Head, Human→authorized subordinate, Worker→Human and Worker→Worker are first-class.
5. Founder is not a routing dependency.
6. Channel loss/replacement does not lose institutional state.
7. Front Office reads/writes through Workplace application APIs; canonical internal `/workforce/*` APIs remain non-public.
8. Dashboard is one management view, not the Workplace product itself.

## Required application surfaces / API projections
- `/workplace/` shell
- `/workplace/api/home`
- `/workplace/api/inbox`
- `/workplace/api/conversations/*`
- `/workplace/api/work/*`
- `/workplace/api/objectives/*`
- `/workplace/api/meetings/*`
- `/workplace/api/decisions/*`
- `/workplace/api/reviews/*`
- `/workplace/api/reports/*`
- `/workplace/api/people/*`
- `/workplace/api/notifications/*`
- `/workplace/api/escalations/*`
- `/workplace/api/management/*`
- `/workplace/api/admin/*`

All are authenticated Workplace presentation/application boundaries. Internal Workforce semantics remain behind them.

## Completion definition
Workplace is complete only when the surfaces above are live, durable, mobile usable, provenance-preserving, channel-independent, integrated with canonical Workforce semantics, protected by authentication/authorization, and production acceptance proves core Human↔Worker and Worker↔Worker operating loops without Founder acting as task router.