package com.metatron.workforce.workers.audit;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Internal execution surface. Authentication/admission occurs upstream. */
@RestController
@RequestMapping("/workforce/execution/repository-audit")
public final class RepositoryAuditExecutionController {
    private final RepositoryAuditExecutionService service;

    public RepositoryAuditExecutionController(RepositoryAuditExecutionService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<?> execute(
            @RequestHeader("X-Metatron-Actor") String actor,
            @RequestHeader("X-Metatron-Authority") String authorityReference,
            @RequestHeader("X-Metatron-Authorization") String authorizationReference,
            @RequestBody AuditCommand command) {
        try {
            RepositoryAuditExecutionService.ExecutionReceipt receipt = service.execute(
                    actor, authorityReference, authorizationReference,
                    command.organizationContextId(), command.repository());
            HttpStatus status = receipt.work().status().name().equals("COMPLETED")
                    ? HttpStatus.OK : HttpStatus.UNPROCESSABLE_ENTITY;
            return ResponseEntity.status(status).body(receipt);
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(Map.of("status", "REJECTED", "reason", invalid.getMessage()));
        }
    }

    public record AuditCommand(String organizationContextId, String repository) {}
}
