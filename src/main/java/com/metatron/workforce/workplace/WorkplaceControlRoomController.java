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
        controlRoom.worker(workerId);
        return chatStore.history(workerId);
    }

    @PostMapping("/workers/{workerId}/chat")
    public WorkplaceWorkerChatStore.ChatTurn chat(
            @PathVariable String workerId,
            @RequestHeader(value="Authorization", required=false) String authorization,
            @RequestBody ChatCommand command) {
        requireAuthenticated(authorization);
        controlRoom.worker(workerId);
        if (command == null || command.message() == null || command.message().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message required");
        }
        String role = command.role() == null || command.role().isBlank()
                ? controlRoom.primaryRole(workerId) : command.role().trim();
        String context = chatStore.context(workerId, 16, 14_000);
        WorkerConversationGateway.Reply reply = workerConversation.converse(
                workerId, role, command.message().trim(), context);
        return chatStore.append(workerId, command.message(), reply);
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
