# Worker Formation & Usability Gap Closure

Status: IMPLEMENTATION + ACCEPTANCE CONTRACT — 2026-09-11

## Problem
The Work surface previously treated Worker formation as a special-case Gateway Director control. Arbitrary requests such as `Create for me a worker, role composer/artist` fell through to generic Intelligence, which could narrate institutional state without any Worker mutation.

A Worker is not a provider persona. Creation is complete only when canonical identity, participation, constitution, capability/profile and runtime are materialized durably and the Worker can actually be selected and used.

## Locked ownership

```text
Telegram / Web / API
        ↓
WORK lifecycle ingress
        ↓
Founder-defined formation
        ↓
Workforce Core
  Participant / Worker / Participation
        ↓
Worker Constitution
        ↓
Capability + Runtime Profile
        ↓
Runtime Instance
        ↓
Direct Worker / Meeting / Workplace use
```

Role text does not grant execution authority or tool access. Founder-defined generic Workers start with bounded cognitive/conversation capability only. Additional shell/Git/deploy or other effectful capability must be granted through its own governed path.

## Gap closure

### W1 — Generic lifecycle ingress
Create/provision/activate/inspect Worker intents are intercepted before generic Intelligence for arbitrary valid roles. Gateway Director retains its canonical policy path.

### W2 — Durable Founder-defined formation
Formation persists Participant, Worker, active Participation, capability attestations, qualification and availability through Workforce Core.

### W3 — Constitution + runtime
Every Founder-defined Worker receives a durable Position operating contract, bounded runtime profile and RUNNING Runtime Instance. The default profile is `runtime-profile:founder-cognitive-worker:v1` and does not self-grant repository, shell, deploy or production authority.

### W4 — Real usability
A successfully formed Worker is visible in the active Worker directory and can be selected through `/worker <worker-id>` or `talk to <role>`. Conversation uses canonical Worker identity, durable memory, constitution, runtime and Worker Intelligence rather than provider role-play.

### W5 — Persistence and recovery
Core identity, Position constitution, profile binding and runtime state use existing durable stores. Acceptance recreates services from disk and requires the same Worker to remain ACTIVE, discoverable and RUNNING.

### W6 — Institutional truth
Malformed/unresolvable lifecycle requests are handled fail-closed and may not fall through to an LLM that claims CREATED/ACTIVE/ACCEPTED. Successful responses are rendered only from observed canonical state.

## Acceptance target — Composer Artist

```text
Create for me a worker, role composer/artist...
→ WORKER-COMPOSER-ARTIST
→ ACTIVE Worker + ACTIVE Participation
→ persisted Position constitution
→ founder cognitive runtime profile
→ RUNNING runtime
→ directory resolution by role
→ immediate activate-it follow-up resolves same Worker
→ process/service reconstruction
→ same Worker still ACTIVE/RUNNING/discoverable
```

`FounderDefinedWorkerFormationAcceptanceTest` machine-checks this path plus malformed creation fail-closed behavior.

## Definition of done
Generic Worker formation is complete only when test/build pass, the change is integrated onto the current canonical release line, deployed as one exact immutable SHA, and a real Telegram E2E proves create → canonical state → direct Worker selection/use after deployment.
