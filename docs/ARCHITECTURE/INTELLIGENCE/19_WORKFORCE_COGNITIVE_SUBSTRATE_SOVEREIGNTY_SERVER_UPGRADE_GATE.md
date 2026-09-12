# WORKFORCE COGNITIVE SUBSTRATE SOVEREIGNTY — SERVER UPGRADE PRE-IMPLEMENTATION GATE

**Status:** GATE RELEASED — IMPLEMENTATION AUTHORIZED  
**Founder instruction:** server/infrastructure review completed; implementation explicitly authorized on 2026-09-12

The pre-implementation hold has been released. This document remains the canonical record of the infrastructure decision and does not itself certify Cognition Node capacity or production cutover.

```text
GATE = PRE_IMPLEMENTATION_SERVER_UPGRADE_REVIEW
STATE = RELEASED
RELEASED_AT = 2026-09-12
```

The current control-plane host and future Cognition Node solve different problems; the review must not collapse control-plane capacity and GPU/inference capacity into one resize decision.

Required review evidence includes CPU/load, RAM/available memory, swap, root disk, Docker/build cache/volume retention, runtime artifacts/backlog, build/test peak pressure, network/private connectivity, production container reservations, planned execution concurrency, restart/reboot risk and MCP/control-plane resilience.

Founder review must explicitly decide: current control-plane host keep/resize/replace; cleanup requirements; default separate Cognition Node topology or approved alternative; and implementation HOLD vs APPROVED TO START.

Server review does not authorize production GPU sizing. Cognition hardware is selected only after qualification measures context, throughput, concurrency, queue SLA, task completion and GPU resource use.

Before release, the following restrictions applied:

```text
NO CODE IMPLEMENTATION
NO RUNTIME WIRING
NO PROVIDER-CREDENTIAL MIGRATION
NO COGNITION NODE DEPLOYMENT
NO PRODUCTION CUTOVER
```

## Release record — 2026-09-12

Observed control-plane state at release:

- host `ubuntu-4gb-sin-2`, uptime about 20 days;
- root filesystem 75G total, 48G used, 25G available (66% used);
- memory 3.7GiB total, about 2.0GiB available at review time;
- swap 6.0GiB total with about 295MiB used;
- broker `3.5.0`;
- production Workforce and Workforce sandbox healthy;
- MCP runtime g8 active + standby healthy; router A/B healthy.

Founder decisions:

```text
CONTROL_PLANE_HOST = KEEP FOR CONTROL-PLANE / WORKFORCE DUTIES
COGNITION_NODE_TOPOLOGY = SEPARATE INFRASTRUCTURE REQUIRED BY DEFAULT
CLEANUP = SUFFICIENT TO PROCEED; CONTINUE NORMAL CAPACITY HYGIENE
IMPLEMENTATION = APPROVED TO START
PRODUCTION_GPU_SIZING = NOT YET AUTHORIZED; QUALIFICATION EVIDENCE REQUIRED
```

The 4GB control-plane host is therefore not approved as the production inference host. The release authorizes implementation and qualification work only; Cognition Node production sizing and production cutover remain gated by the later acceptance phases.
