# P10 DEPLOYMENT FAILURE — 2026-08-31

Status: RECOVERED BY AUTOMATIC ROLLBACK

Candidate SHA: `5efe6ca7dd26bf5272e68008fda54a8d7c15aa3f`
Previous production SHA: `30a9f952c6e062a32de598a93f9759dd9ee9e69c`
Production Deploy run: `33381019576`

## Failure

The candidate image built successfully but Spring could not instantiate `RepositoryPullRequestAutonomousCapability` because the production constructor was not explicitly selected while a second package-private test constructor also existed. The application repeatedly failed during context initialization.

## Recovery

The production deployment workflow detected that the candidate never became healthy and automatically restored the known-good image. The rollback health probe returned `ROLLBACK=PASS`.

## Required correction

- explicitly mark the production `ObjectMapper` constructor for Spring injection;
- add a Spring-container wiring regression test so CI proves bean construction rather than only direct unit construction;
- redeploy the corrected exact SHA before any P10 Golden Slice is credited.

This incident is deployment/recovery evidence only. It does not itself satisfy Golden Slice 3 or `ACCEPTED_L10`.
