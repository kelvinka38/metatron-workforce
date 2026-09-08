package com.metatron.workforce;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Boots the actual production application package. This guards against regressions where
 * compile/unit tests pass but Spring cannot construct production beans at runtime.
 */
@SpringBootTest(
        classes = MetatronWorkforceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MetatronWorkforceApplicationStartupTest {

    @Test
    void productionApplicationContextStarts() {
    }
}
