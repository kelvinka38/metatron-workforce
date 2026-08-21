# METATRON WORKFORCE — PHASE 2 / 06 EVENT MODEL

## 1. Event definition

An event represents a material institutional fact that occurred or a material state transition that was accepted.

Events are not arbitrary internal callbacks and must not represent speculative intent.

## 2. Canonical event families

### Identity / participation

```text
ParticipantRecognized
WorkerAdmitted
WorkerActivated
WorkerRetired
ParticipationCreated
ParticipationActivated
ParticipationSuspended
ParticipationEnded
```

### Organization / role

```text
RoleAssigned
RoleChanged
ReportingRelationshipCreated
ReportingRelationshipEnded
DelegationGranted
DelegationRevoked
```

### Capability / qualification

```text
CapabilityClaimed
CapabilityDemonstrated
CapabilityAssessed
QualificationGranted
QualificationExpired
QualificationRevoked
```

### Work

```text
WorkRequested
WorkProposed
WorkSubmitted
WorkApproved
WorkRejected
WorkReturned
AssignmentCreated
AssignmentApproved
AssignmentCancelled
AssignmentCompleted
```

### Authorization / execution

```text
AuthorizationRequested
AuthorizationAllowed
AuthorizationDenied
AuthorizationDeferred
ExecutionRequested
ExecutionStarted
ExecutionCompleted
ExecutionFailed
ExecutionBlocked
ExecutionCancelled
```

### Workplace

```text
ConversationCreated
MessageSent
MeetingCreated
MeetingHeld
DecisionRecorded
ActionItemCreated
```

### Time / capacity / staffing

```text
ScheduleCreated
AvailabilityChanged
CapacityChanged
CapacityCommitted
CapacityReleased
StaffingShortageDetected
StaffingPlanChanged
```

### Reporting / performance

```text
ReportSubmitted
ReportReviewed
ReportReturned
PerformanceEvaluated
VarianceDetected
```

### Evidence / learning

```text
OutcomeRecorded
ObservationRecorded
EvidenceRecorded
ExperienceRecorded
ReflectionRecorded
LearningCandidateCreated
ImprovementProposed
ImprovementEvaluated
ImprovementValidated
ImprovementRejected
ImprovementAdopted
```

Event naming may follow implementation conventions, but the above semantics must remain available.

## 3. Event envelope semantics

Every material event MUST be attributable and temporally interpretable. Conceptually it should preserve:

- event_id;
- event_type;
- aggregate/entity reference;
- actor/initiator;
- authority context;
- organizational context;
- assignment/authorization context where applicable;
- occurred_at;
- recorded_at;
- correlation_id;
- causation_id;
- provenance/evidence references;
- relevant state transition.

The exact serialized envelope is an implementation decision.

## 4. Event rules

1. Events represent material facts, not speculative intent.
2. Events are attributable.
3. Events carry sufficient context to reconstruct the material transition.
4. Events preserve temporal meaning.
5. Events cannot bypass authorization.
6. Publishing a Workforce event does not imply an external domain accepted the fact unless that domain acknowledged it.
7. Consumers must tolerate duplicate delivery where at-least-once delivery is used.
8. Material transitions must be idempotently enforceable.

## 5. Event causality

The implementation must preserve enough causality to distinguish:

```text
Human / Worker instruction
        ↓
COMMAND / REQUEST
        ↓
STATE TRANSITION
        ↓
EVENT
        ↓
DOWNSTREAM CONSEQUENCE
```

## 6. Event versus command

```text
COMMAND = requested intent
EVENT   = accepted material fact
```

A rejected command does not emit the corresponding successful transition event.

## 7. External-domain events

Workforce may consume or emit integration facts concerning Governance, Gateway, Execution, Observation, Knowledge, Economy, and other domains, but must preserve ownership boundaries. An external acknowledgment is a separate fact from Workforce's local publication.

## 8. Implementation constraint

This model does not prescribe Kafka, queues, event sourcing, database triggers, or any other transport/storage technology.