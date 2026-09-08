package com.metatron.workforce;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Boots the actual production application package. This guards against regressions where
 * compile/unit tests pass but Spring cannot construct production beans at runtime.
 */
@SpringBootTest(
        classes = MetatronWorkforceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "METATRON_INTELLIGENCE_CASE_PATH=build/test-runtime/intelligence-cases",
                "METATRON_INTELLIGENCE_DEPTH_PATH=build/test-runtime/intelligence-depth",
                "METATRON_WORKPLACE_MEETING_PATH=build/test-runtime/workplace/meetings",
                "METATRON_WORKFORCE_CORE_STATE_PATH=build/test-runtime/workforce-core-state.json",
                "METATRON_MANAGEMENT_STATE_PATH=build/test-runtime/management-state.json",
                "METATRON_AUTONOMY_COORDINATION_STATE_PATH=build/test-runtime/autonomy-coordination-state.json",
                "METATRON_AUTONOMY_SAFETY_STATE_PATH=build/test-runtime/autonomy-safety-state.json",
                "METATRON_AUTONOMY_SCHEDULING_STATE_PATH=build/test-runtime/autonomy-scheduling-state.json",
                "METATRON_OBSERVATION_STATE_PATH=build/test-runtime/observation-state.json",
                "METATRON_EXECUTION_ATTEMPT_STATE_PATH=build/test-runtime/execution-attempts.json",
                "METATRON_RUNTIME_STATE_DIR=build/test-runtime/runtime-state",
                "METATRON_WORKFORCE_SCHEDULE_STATE_PATH=build/test-runtime/work-schedules.json",
                "METATRON_WORKFORCE_STAFFING_STATE_PATH=build/test-runtime/staffing-state.json",
                "METATRON_WORKFORCE_REVIEW_STATE_PATH=build/test-runtime/review-state.json",
                "METATRON_WORKFORCE_WORK_STATE_PATH=build/test-runtime/institutional-work.json",
                "METATRON_WORKPLACE_CONTINUITY_STATE_PATH=build/test-runtime/workplace-continuity-state.json",
                "METATRON_RUNTIME_PROFILE_BINDINGS_PATH=build/test-runtime/runtime-profile-bindings.tsv",
                "METATRON_OBJECTIVE_WORKSPACE_ROOT=build/test-runtime/objective-workspaces",
                "OPENAI_API_KEY=startup-test-placeholder",
                "OPENAI_MODEL=startup-test-model"
        })
class MetatronWorkforceApplicationStartupTest {

    @Test
    void productionApplicationContextStarts() {
    }
}
