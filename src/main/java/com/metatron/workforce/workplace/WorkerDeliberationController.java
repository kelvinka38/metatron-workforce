package com.metatron.workforce.workplace;

import com.metatron.workforce.deliberation.WorkerDeliberationRuntime;
import com.metatron.workforce.deliberation.WorkerDeliberationState;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Read-only Workplace projection of the universal Worker deliberation state. */
@RestController
@RequestMapping("/workplace/api")
public final class WorkerDeliberationController {
    private final WorkplaceDashboardAuthService authentication;
    private final WorkerDeliberationRuntime deliberation;

    public WorkerDeliberationController(
            WorkplaceDashboardAuthService authentication,
            WorkerDeliberationRuntime deliberation) {
        this.authentication = authentication;
        this.deliberation = deliberation;
    }

    @GetMapping("/workers/{workerId}/deliberation")
    public WorkerDeliberationState state(
            @PathVariable String workerId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (!authentication.valid(WorkplaceDashboardController.bearer(authorization))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Workplace authentication required");
        }
        return deliberation.state(workerId);
    }
}
