# Telegram Repository Audit Delegation

Status: ACTIVE — bounded production delegation

## Purpose

Permit the already-resolved institutional human actor `telegram-human` to request the read-only BIOS repository-audit capability through the authenticated Telegram channel without turning message text into institutional authority.

## Invariant

`TELEGRAM MESSAGE ≠ AUTHORITY`

The message only selects an already-delegated capability. Authorization comes from this preconfigured bounded delegation and is enforced by the institutional execution path and Gateway-governed egress.

## Delegated capability

- actor: `telegram-human`
- organization: configured `METATRON_ORGANIZATION_ID`
- action: read-only repository audit
- object: `kelvinka38/bios`
- worker: `WORKER-REPOSITORY-AUDITOR`
- external destination: GitHub API through governed Gateway egress
- mutation: forbidden
- authority reference: `policy:telegram-founder-repository-audit:v1`
- authorization reference: `authorization:telegram-founder-readonly-repository-audit:v1`

## Admission

Only the exact bounded intents `Audit BIOS` and `/audit bios` are admitted by this delegation. Other text continues through the normal interaction path and gains no execution authority from this policy.

The configured Telegram identity resolver remains fail-closed: an unknown Telegram user is not provisioned and cannot use this delegation.

## Evidence

Every admitted audit must create institutional Work, execute through `RepositoryAuditExecutionService`, preserve runtime evidence, use `gateway-egress/github-api`, and terminate as `COMPLETED` or `BLOCKED`. A successful result is returned through the Telegram bot transport.
