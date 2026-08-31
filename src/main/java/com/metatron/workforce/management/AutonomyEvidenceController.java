package com.metatron.workforce.management;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal canonical progress/evidence projection; external publication remains a Workplace/Gateway concern. */
@RestController
@RequestMapping("/workforce/management/objectives/{objectiveId}")
public final class AutonomyEvidenceController {
    private final AutonomyEvidencePackageService evidence;

    public AutonomyEvidenceController(AutonomyEvidencePackageService evidence) {
        this.evidence = evidence;
    }

    @GetMapping("/progress")
    public AutonomyEvidencePackageService.ObjectiveEvidencePackage progress(@PathVariable String objectiveId) {
        return evidence.packageFor(objectiveId);
    }

    @GetMapping("/completion-package")
    public AutonomyEvidencePackageService.ObjectiveEvidencePackage completionPackage(@PathVariable String objectiveId) {
        return evidence.packageFor(objectiveId);
    }
}
