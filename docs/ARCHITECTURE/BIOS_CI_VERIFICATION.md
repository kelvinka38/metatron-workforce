# BIOS CI VERIFICATION

## Purpose

This file records the CI verification gate for the Workforce BIOS conformance boundary.

## Gate

- Deterministic `BiosConformanceValidator` runtime gate implemented.
- Unit tests added for evidence, authority, consequence, provider attribution and output invariants.
- CI must execute `./gradlew clean test bootJar --no-daemon` on the resulting `main` commit before the BIOS boundary is promoted to verified.

## Status

PENDING CI RESULT.
