package com.zsumz.logyard.tests.verification;

import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.config.LogyardConfig;
import com.zsumz.logyard.runtime.assembly.LogyardRuntimeFactory;
import com.zsumz.logyard.tests.extensions.TestOutputProvider;

import static com.zsumz.logyard.tests.verification.VerificationAssertions.equal;
import static com.zsumz.logyard.tests.verification.VerificationFixtures.extensionConfig;

final class AsyncDeliveryVerification implements VerificationCase {
    private static final int EVENT_COUNT = 2_000;

    @Override
    public String description() {
        return "bounded asynchronous logging delivery";
    }

    @Override
    public void verify() {
        TestOutputProvider.reset();
        LogyardConfig config = extensionConfig("allow = true", "marker = \"smoke\"", 4_096);
        try (LogyardRuntime runtime = LogyardRuntimeFactory.create(config)) {
            for (int index = 0; index < EVENT_COUNT; index++) {
                runtime.logger("tests.Smoke").atInfo().add("index", index).log("event {}", index);
            }
            runtime.flush();
        }
        equal((long) EVENT_COUNT, TestOutputProvider.deliveredCount());
        equal((long) EVENT_COUNT, TestOutputProvider.snapshot().deliveredCount());
    }
}
