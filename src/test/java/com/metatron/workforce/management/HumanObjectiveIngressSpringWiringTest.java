package com.metatron.workforce.management;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.interaction.intelligence.ExecutionPlanProposalService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.List;
import java.util.Map;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HumanObjectiveIngressSpringWiringTest {

    @Test
    void springSelectsTheProductionConstructorAndInjectsCapabilityInventory() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "test", Map.of("workforce.management.head-worker-id", "worker-head")));
            context.registerBean(ManagementAutonomyService.class);
            context.registerBean("testAutonomousCapability", AutonomousExecutionCapability.class,
                    HumanObjectiveIngressSpringWiringTest::testCapability);
            context.registerBean(ExecutionPlanProposalService.class,
                    () -> (caseId, request, available) -> request.executionWorkPlan());
            context.registerBean(AutonomousManagementRunner.class, () ->
                    new AutonomousManagementRunner(
                            context.getBean(ManagementAutonomyService.class),
                            context.getBean(ExecutionPlanProposalService.class),
                            List.of(context.getBean("testAutonomousCapability", AutonomousExecutionCapability.class)),
                            Clock.systemUTC()));
            context.register(HumanObjectiveIngressService.class);
            context.refresh();

            HumanObjectiveIngressService ingress = context.getBean(HumanObjectiveIngressService.class);
            assertEquals(List.of("test.read"), ingress.capabilityCatalog());
        }
    }

    private static AutonomousExecutionCapability testCapability() {
        return new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return "test.read"; }
            @Override public CapabilityResult execute(CapabilityRequest request) {
                ExecutionWorkSpec step = request.workSpec();
                return new CapabilityResult(true, "worker-test", "assignment-test", "work-test",
                        List.of("evidence-test"), step.objective());
            }
        };
    }
}
