package com.metatron.workforce.workplace;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.interaction.DirectWorkerConversationService;
import com.metatron.workforce.interaction.memory.PersistentWorkerConversationMemoryStore;
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
    private final DirectWorkerConversationService directWorkerConversation;
    private final WorkerOperatingProfileService workerOperatingProfile;
    private final WorkplaceFounderControlService founderControl;

    public WorkplaceControlRoomController(
            WorkplaceDashboardAuthService authentication,
            WorkplaceControlRoomService controlRoom,
            WorkforceCoreService core,
            DirectWorkerConversationService directWorkerConversation,
            WorkerOperatingProfileService workerOperatingProfile,
            WorkplaceFounderControlService founderControl) {
        this.authentication = authentication;
        this.controlRoom = controlRoom;
        this.core = core;
        this.directWorkerConversation = directWorkerConversation;
        this.workerOperatingProfile = workerOperatingProfile;
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

    @GetMapping("/workers/{workerId}/profile")
    public WorkerOperatingProfileService.OperatingProfile workerProfile(
            @PathVariable String workerId,
            @RequestHeader(value="Authorization", required=false) String authorization) {
        requireAuthenticated(authorization);
        try {
            return workerOperatingProfile.profile(controlRoom.resolveConversationWorkerId(workerId));
        } catch (RuntimeException failure) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, failure.getMessage(), failure);
        }
    }

    @GetMapping("/workers/{workerId}/chat")
    public List<PersistentWorkerConversationMemoryStore.Turn> chatHistory(
            @PathVariable String workerId,
            @RequestHeader(value="Authorization", required=false) String authorization) {
        requireAuthenticated(authorization);
        try {
            return directWorkerConversation.history("human-primary", workerId);
        } catch (IllegalStateException failure) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, failure.getMessage(), failure);
        }
    }

    @PostMapping("/workers/{workerId}/chat")
    public PersistentWorkerConversationMemoryStore.Turn chat(
            @PathVariable String workerId,
            @RequestHeader(value="Authorization", required=false) String authorization,
            @RequestBody ChatCommand command) {
        requireAuthenticated(authorization);
        if (command == null || command.message() == null || command.message().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message required");
        }
        try {
            return directWorkerConversation.converse(
                    "human-primary", workerId, command.message().trim(), "workplace").turn();
        } catch (IllegalStateException failure) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, failure.getMessage(), failure);
        }
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
