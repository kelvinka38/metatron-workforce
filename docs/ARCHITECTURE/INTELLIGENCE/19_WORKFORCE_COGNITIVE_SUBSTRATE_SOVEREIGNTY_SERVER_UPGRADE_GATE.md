# WORKFORCE COGNITIVE SUBSTRATE SOVEREIGNTY — SERVER UPGRADE PRE-IMPLEMENTATION GATE

**Status:** MANDATORY HOLD  
**Founder instruction:** review server upgrade before implementation

No Sovereignty implementation task may begin until the Founder reviews server/infrastructure state and explicitly releases this gate.

```text
GATE = PRE_IMPLEMENTATION_SERVER_UPGRADE_REVIEW
STATE = BLOCKING
```

The current control-plane host and future Cognition Node solve different problems; the review must not collapse control-plane capacity and GPU/inference capacity into one resize decision.

Required review evidence includes CPU/load, RAM/available memory, swap, root disk, Docker/build cache/volume retention, runtime artifacts/backlog, build/test peak pressure, network/private connectivity, production container reservations, planned execution concurrency, restart/reboot risk and MCP/control-plane resilience.

Founder review must explicitly decide: current control-plane host keep/resize/replace; cleanup requirements; default separate Cognition Node topology or approved alternative; and implementation HOLD vs APPROVED TO START.

Server review does not authorize production GPU sizing. Cognition hardware is selected only after qualification measures context, throughput, concurrency, queue SLA, task completion and GPU resource use.

Until a release record exists:

```text
NO CODE IMPLEMENTATION
NO RUNTIME WIRING
NO PROVIDER-CREDENTIAL MIGRATION
NO COGNITION NODE DEPLOYMENT
NO PRODUCTION CUTOVER
```
