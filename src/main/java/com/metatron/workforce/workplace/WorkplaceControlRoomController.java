package com.metatron.workforce.workplace;

import com.metatron.workforce.core.WorkforceCoreService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/** Interactive authenticated application API for the Workplace Control Room. */
@RestController
@RequestMapping("/workplace/api")
public final class WorkplaceControlRoomController {
    private final WorkplaceDashboardAuthService authentication;
    private final WorkplaceControlRoomService controlRoom;
    private final WorkforceCoreService core;
    private final WorkerConversationGateway workerConversation;
    private final WorkplaceWorkerChatStore chatStore;
    private final WorkplaceFounderControlService founderControl;

    public WorkplaceControlRoomController(
            WorkplaceDashboardAuthService authentication,
            WorkplaceControlRoomService controlRoom,
            WorkforceCoreService core,
            WorkerConversationGateway workerConversation,
            WorkplaceWorkerChatStore chatStore,
            WorkplaceFounderControlService founderControl) {
        this.authentication = authentication;
        this.controlRoom = controlRoom;
        this.core = core;
        this.workerConversation = workerConversation;
        this.chatStore = chatStore;
        this.founderControl = founderControl;
    }

    @GetMapping("/control-room")
    public WorkplaceControlRoomService.ControlRoomSnapshot snapshot(
            @RequestHeader(value="Authorization", required=false) String authorization) {
        requireAuthenticated(authorization);
        return controlRoom.snapshot();
    }

    @GetMapping("/workers/{workerId}")
    public WorkplaceControlRoomService.WorkerDetail worker(
            @PathVariable String workerId,
            @RequestHeader(value="Authorization", required=false) String authorization) {
        requireAuthenticated(authorization);
        return controlRoom.worker(workerId);
    }

    @GetMapping("/workers/{workerId}/chat")
    public List<WorkplaceWorkerChatStore.ChatTurn> chatHistory(
            @PathVariable String workerId,
            @RequestHeader(value="Authorization", required=false) String authorization) {
        requireAuthenticated(authorization);
        try {
            WorkplaceControlRoomService.WorkerDetail detail = controlRoom.prepareConversationWorker(workerId);
            return chatStore.history(detail.workerId());
        } catch (IllegalStateException failure) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, failure.getMessage(), failure);
        }
    }

    @PostMapping("/workers/{workerId}/chat")
    public WorkplaceWorkerChatStore.ChatTurn chat(
            @PathVariable String workerId,
            @RequestHeader(value="Authorization", required=false) String authorization,
            @RequestBody ChatCommand command) {
        requireAuthenticated(authorization);
        if (command == null || command.message() == null || command.message().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message required");
        }

        WorkplaceControlRoomService.WorkerDetail detail;
        try {
            detail = controlRoom.prepareConversationWorker(workerId);
        } catch (IllegalStateException failure) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, failure.getMessage(), failure);
        }

        String effectiveWorkerId = detail.workerId();
        String role = command.role() == null || command.role().isBlank()
                ? detail.primaryRole() : command.role().trim();
        String history = chatStore.context(effectiveWorkerId, 16, 11_000);
        String context = workerOperationalContext(detail) + (history.isBlank() ? "" : "\n\nRECENT DIRECT CONVERSATION\n" + history);
        List<String> trustedExecutionEvidence = detail.actions().stream()
                .flatMap(action -> action.evidenceReferences().stream())
                .filter(ref -> ref != null && ref.startsWith("action-fabric:"))
                .filter(ref -> ref.contains(":worker=" + effectiveWorkerId + ":"))
                .filter(ref -> ref.endsWith(":success=true") || ref.contains(":success=true:"))
                .distinct()
                .limit(200)
                .toList();
        WorkerConversationGateway.Reply reply = workerConversation.converse(
                effectiveWorkerId, role, command.message().trim(), context, trustedExecutionEvidence);
        return chatStore.append(effectiveWorkerId, command.message(), reply);
    }

    @PostMapping("/workers/{workerId}/availability")
    public WorkforceCoreService.Availability availability(
            @PathVariable String workerId,
            @RequestHeader(value="Authorization", required=false) String authorization,
            @RequestBody AvailabilityCommand command) {
        requireAuthenticated(authorization);
        if (command == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "command required");
        double capacity = command.available()
                ? (command.capacity() == null ? currentCapacity(workerId) : command.capacity())
                : (command.capacity() == null ? currentCapacity(workerId) : command.capacity());
        return core.setAvailability(workerId, command.available(), capacity);
    }

    @PostMapping("/objectives/{objectiveId}/control/{action}")
    public WorkplaceFounderControlService.ControlResult controlObjective(
            @PathVariable String objectiveId,
            @PathVariable String action,
            @RequestHeader(value="Authorization", required=false) String authorization,
            @RequestBody(required=false) Map<String,String> body) {
        requireAuthenticated(authorization);
        return switch (action.toLowerCase(java.util.Locale.ROOT)) {
            case "pause" -> founderControl.pause(objectiveId);
            case "resume" -> founderControl.resume(objectiveId);
            case "cancel" -> founderControl.cancel(objectiveId);
            case "replan" -> founderControl.replan(objectiveId, body == null ? "" : body.get("reason"));
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported control action");
        };
    }

    private static String workerOperationalContext(WorkplaceControlRoomService.WorkerDetail detail) {
        StringBuilder out = new StringBuilder("CURRENT CANONICAL WORKPLACE PROJECTION\n");
        out.append("worker_id=").append(detail.workerId()).append('\n')
                .append("worker_status=").append(detail.status()).append('\n')
                .append("role=").append(detail.primaryRole()).append('\n')
                .append("runtime_id=").append(detail.runtimeId()).append('\n')
                .append("runtime_state=").append(detail.runtimeState()).append('\n')
                .append("runtime_profile=").append(detail.runtimeProfile()).append('\n');
        if (detail.availability() != null) {
            out.append("available=").append(detail.availability().available()).append('\n')
                    .append("capacity=").append(detail.availability().capacity()).append('\n');
        }
        out.append("OBJECTIVES\n");
        detail.objectives().stream().limit(12).forEach(objective ->
                out.append("- ").append(objective.objectiveId())
                        .append(" | ").append(objective.executionState())
                        .append(" | ").append(objective.completedWork()).append('/').append(objective.totalWork())
                        .append(" | ").append(objective.summary()).append('\n'));
        out.append("TASKS\n");
        detail.tasks().stream().limit(24).forEach(task ->
                out.append("- ").append(task.objectiveId()).append('/').append(task.stepId())
                        .append(" | ").append(task.state())
                        .append(" | performer=").append(task.performer())
                        .append(" | actions=").append(task.actionCount())
                        .append(" | ").append(task.task()).append('\n'));
        out.append("RECENT ACTIONS\n");
        detail.actions().stream().limit(20).forEach(action ->
                out.append("- ").append(action.recordedAt())
                        .append(" | ").append(action.actionRef())
                        .append(" | success=").append(action.success())
                        .append(" | consequence=").append(action.consequence())
                        .append(" | ").append(action.summary()).append('\n'));
        return out.toString();
    }

    private double currentCapacity(String workerId) {
        return core.availability(workerId).map(WorkforceCoreService.Availability::capacity).orElse(1.0);
    }

    private void requireAuthenticated(String authorization) {
        if (!authentication.valid(WorkplaceDashboardController.bearer(authorization))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Workplace authentication required");
        }
    }

    public record ChatCommand(String message, String role) {}
    public record AvailabilityCommand(boolean available, Double capacity) {}
}
