# PRODUCTION_ARCHITECTURE.md

## Status

APPROVED FOR CURRENT CERTIFICATION ENVELOPE

## Logical Boundary

```text
Gateway / Human Actor
        ↓
Workforce
        ↓
Assignment / Authorization
        ↓
Execution
        ↓
Worker Runtime
        ↓
Technical Persistence + Evidence
```

## Current Deployable Runtime

- Spring Boot application packaged as a deployable JAR;
- Java 22 runtime;
- `WorkforceRuntime` owns runtime implementation lifecycle only;
- `RuntimePersistenceStore` is the replaceable persistence boundary;
- `FileRuntimePersistenceStore` provides durable single-node continuity;
- `RemoteRuntimeExecutor` provides an asynchronous HTTP transport boundary when the runtime is hosted outside the Workforce JVM;
- remote execution carries execution/worker/runtime attribution only;
- production evidence is written to an explicit evidence directory;
- GitHub Actions provides repeatable acceptance and evidence packaging.

## Invariants

- Worker identity is never replaced by runtime identity;
- Runtime identity is never used as Execution identity;
- persistence stores technical continuity metadata only;
- remote transport does not become an authority boundary;
- infrastructure choices do not redefine institutional semantics.

## Scaling Boundary

The current architecture is certified only for the verified acceptance envelope. Distributed persistence, multi-node orchestration, and a remote runtime fleet remain replaceable infrastructure choices and require measured workload/capacity evidence before being promoted to a production-scale topology.
