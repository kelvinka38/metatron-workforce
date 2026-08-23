# G12 Certification Attempt

## Purpose

This document records the final certification-trigger commit for G12 Production Readiness.

## Certification Rule

G12 is not accepted from local evidence alone. The canonical certification requires the GitHub Actions production-readiness workflow to execute against the exact main-branch commit and pass all enforced assertions.

## Required Assertions

- full acceptance suite passes;
- durable runtime recovery passes;
- all G12 acceptance tests pass;
- deployable JAR builds and executes;
- persisted runtime state is produced;
- production evidence is produced;
- same-organization visibility is true;
- cross-organization visibility is false;
- runtime state after replacement is RUNNING;
- runtime recovery metric is 1;
- execution success metric is 1;
- attributable runtime logs exist;
- execution and authorization identifiers exist in traces;
- complete evidence bundle is uploaded.

## Decision

PENDING — awaiting fresh GitHub Actions execution for the exact commit containing this record.
