# METATRON WORKFORCE — PHASE 2 / 05 COMMAND MODEL

## 1. Purpose

Commands represent requested state-changing intent entering the Workforce application/workflow boundary.

A command is not an event, authorization, execution, or outcome.

```text
COMMAND
 ≠
AUTHORIZATION
 ≠
EVENT
 ≠
EXECUTION
 ≠
OUTCOME
```

## 2. Command contract

Every material command SHOULD preserve:

- command_id;
- command_type;
- requester/actor;
- Worker/context reference where applicable;
- target entity/reference;
- requested action;
- organizational context;
- authority context;
- assignment context where applicable;
- temporal context;
- idempotency key where applicable;
- correlation/causation reference;
- input evidence;
- received_at.

## 3. Canonical command families

### Identity / participation

- RecognizeParticipant
- AdmitWorker
- ActivateWorker
- RetireWorker
- CreateParticipation
- ActivateParticipation
- SuspendParticipation
- EndParticipation

### Organization / relationship

- AssignRole
- ChangeRole
- CreateReportingRelationship
- EndReportingRelationship
- GrantDelegation
- RevokeDelegation

### Capability / qualification

- ClaimCapability
- RecordCapabilityDemonstration
- AssessCapability
- GrantQualification
- ExpireQualification
- RevokeQualification

### Work / planning

- RequestWork
- ProposeWork
- SubmitProposal
- ApproveProposal
- RejectProposal
- ReturnProposal
- CreateAssignment
- ApproveAssignment
- ActivateAssignment
- CompleteAssignment
- CancelAssignment

### Time / capacity / staffing

- CreateSchedule
- ChangeAvailability
- CommitCapacity
- ReleaseCapacity
- ChangeCapacity
- DetectStaffingShortage
- ChangeStaffingPlan

### Authorization / execution coordination

- RequestAuthorization
- ReevaluateAuthorization
- RequestExecution
- BindRuntime
- RecordExecutionResult
- RecordExecutionFailure
- EscalateExecutionBlock

### Workplace

- CreateConversation
- SendMessage
- CreateMeeting
- HoldMeeting
- RecordDecision
- CreateActionItem

### Reporting / performance

- SubmitReport
- ReviewReport
- ReturnReport
- RejectReport
- EvaluatePerformance
- RecordVariance

### Learning / improvement

- RecordExperience
- RecordReflection
- CreateLearningCandidate
- ProposeImprovement
- EvaluateImprovement
- ValidateImprovement
- RejectImprovement
- AdoptImprovement

## 4. Command preconditions

A command handler MUST validate the relevant:

- identity;
- lifecycle state;
- organizational context;
- role/capability/qualification;
- authority;
- assignment;
- authorization requirements;
- temporal validity;
- capacity/resource constraints;
- policy requirements;
- evidence requirements.

## 5. Command outcome

A successful command may produce one or more accepted domain state transitions and corresponding events.

A command MUST NOT be treated as proof that the requested action occurred.

Example:

```text
RequestExecution
   ↓
validate
   ↓
authorization
   ↓
execution admission
   ↓
ExecutionRequested
   ↓
ExecutionStarted / ExecutionFailed / ExecutionBlocked
```

## 6. Idempotency

Material state-changing commands MUST be safely repeatable where the delivery model can produce duplicate requests.

The implementation must prevent duplicate command delivery from creating duplicate material transitions.

## 7. Causality

Commands SHOULD preserve the causal chain to the initiating human/Worker instruction, proposal, assignment, or external request. The exact transport representation is deferred to implementation technology.