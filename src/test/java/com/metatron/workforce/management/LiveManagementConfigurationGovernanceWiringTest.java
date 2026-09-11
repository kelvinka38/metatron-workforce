package com.metatron.workforce.management;

import com.metatron.workforce.execution.governance.ExecutionGate;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveManagementConfigurationGovernanceWiringTest {

    @Test
    void liveAutonomyRunnerRequiresExecutionGate() {
        Method runnerFactory = Arrays.stream(LiveManagementConfiguration.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("autonomousManagementRunner"))
                .findFirst()
                .orElseThrow();

        assertTrue(Arrays.asList(runnerFactory.getParameterTypes()).contains(ExecutionGate.class),
                "production autonomy composition must inject ExecutionGate for mutating capability governance");
    }
}
