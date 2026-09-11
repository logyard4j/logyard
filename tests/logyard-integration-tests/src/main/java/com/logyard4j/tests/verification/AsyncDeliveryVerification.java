package com.logyard4j.tests.verification;

import com.logyard4j.api.LogyardRuntime;
import com.logyard4j.config.LogyardConfig;
import com.logyard4j.runtime.assembly.LogyardRuntimeFactory;
import com.logyard4j.tests.extensions.TestOutputProvider;

import static com.logyard4j.tests.verification.VerificationAssertions.equal;
import static com.logyard4j.tests.verification.VerificationFixtures.extensionConfig;

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
