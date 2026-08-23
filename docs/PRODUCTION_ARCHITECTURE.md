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
- production evidence is written to an explicit evidence directory;
- GitHub Actions provides repeatable acceptance and evidence packaging.

## Invariants

- Worker identity is never replaced by runtime identity;
- Runtime identity is never used as Execution identity;
- persistence stores technical continuity metadata only;
- infrastructure choices do not redefine institutional semantics.

## Scaling Boundary

The current architecture is certified only for the verified acceptance envelope. Distributed persistence, remote worker pools, and multi-node orchestration are intentionally not claimed until workload/capacity evidence requires them.
