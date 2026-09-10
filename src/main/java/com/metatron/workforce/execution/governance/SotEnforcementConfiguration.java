package com.metatron.workforce.execution.governance;

import com.metatron.workforce.execution.ExecutionAttemptService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Clock;

/** Production composition for local, deterministic SoT-bound execution governance. */
@Configuration
public class SotEnforcementConfiguration {

    @Bean
    GovernanceStateStore governanceStateStore(
            @Value("${METATRON_SOT_GOVERNANCE_STATE_PATH:/var/lib/metatron-workforce/sot-enforcement-governance.json}")
            String configured) {
        return new FileGovernanceStateStore(Path.of(configured));
    }

    @Bean
    AuthorityManifestCatalog authorityManifestCatalog() {
        return AuthorityManifestCatalog.classpath();
    }

    @Bean
    SotDiscoveryService sotDiscoveryService(AuthorityManifestCatalog catalog, GovernanceStateStore store) {
        return new SotDiscoveryService(catalog, store, Clock.systemUTC());
    }

    @Bean
    AuthorityFreshnessValidator authorityFreshnessValidator(GovernanceStateStore store) {
        return new AuthorityFreshnessValidator(store);
    }

    @Bean
    ConstraintEvaluator constraintEvaluator() {
        return new ConstraintEvaluator();
    }

    @Bean
    PlanConformanceValidator planConformanceValidator() {
        return new PlanConformanceValidator();
    }

    @Bean
    DerivationValidator derivationValidator(GovernanceStateStore store) {
        return new DerivationValidator(store, Clock.systemUTC());
    }

    @Bean
    GovernancePlanService governancePlanService(SotDiscoveryService discovery,
                                                DerivationValidator derivation,
                                                GovernanceStateStore store) {
        return new GovernancePlanService(discovery, derivation, store, Clock.systemUTC());
    }

    @Bean
    GovernanceAttemptBindingService governanceAttemptBindingService(GovernanceStateStore store) {
        return new GovernanceAttemptBindingService(store, Clock.systemUTC());
    }

    @Bean
    GovernanceAdmissionValidator governanceAdmissionValidator(GovernanceStateStore store,
                                                              AuthorityFreshnessValidator freshness,
                                                              PlanConformanceValidator planConformance) {
        return new GovernanceAdmissionValidator(store, freshness, planConformance);
    }

    @Bean
    ExecutionGate executionGate(GovernanceStateStore store,
                                ExecutionAttemptService attempts,
                                AuthorityFreshnessValidator freshness,
                                PlanConformanceValidator planConformance,
                                ConstraintEvaluator constraints) {
        return new ExecutionGate(store, attempts, freshness, planConformance, constraints, Clock.systemUTC());
    }

    @Bean
    CompletionGate completionGate(GovernanceStateStore store,
                                  AuthorityFreshnessValidator freshness,
                                  ConstraintEvaluator constraints) {
        return new CompletionGate(store, freshness, constraints, Clock.systemUTC());
    }
}
