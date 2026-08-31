package com.metatron.workforce.management;

import com.metatron.workforce.workplace.WorkplaceDashboardAuthService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Internal canonical progress/evidence projection; publication still remains a Workplace/Gateway concern. */
@RestController
@RequestMapping("/workforce/management/objectives/{objectiveId}")
public final class AutonomyEvidenceController {
    private final AutonomyEvidencePackageService evidence;
    private final WorkplaceDashboardAuthService authentication;

    public AutonomyEvidenceController(AutonomyEvidencePackageService evidence,
                                      WorkplaceDashboardAuthService authentication) {
        this.evidence = evidence;
        this.authentication = authentication;
    }

    @GetMapping("/progress")
    public AutonomyEvidencePackageService.ObjectiveEvidencePackage progress(
            @PathVariable String objectiveId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        requireAuthenticated(authorization);
        return evidence.packageFor(objectiveId);
    }

    @GetMapping("/completion-package")
    public AutonomyEvidencePackageService.ObjectiveEvidencePackage completionPackage(
            @PathVariable String objectiveId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        requireAuthenticated(authorization);
        return evidence.packageFor(objectiveId);
    }

    private void requireAuthenticated(String authorization) {
        if (!authentication.valid(bearer(authorization))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Workplace authentication required");
        }
    }

    private static String bearer(String header) {
        return header != null && header.startsWith("Bearer ") ? header.substring(7).trim() : null;
    }
}
