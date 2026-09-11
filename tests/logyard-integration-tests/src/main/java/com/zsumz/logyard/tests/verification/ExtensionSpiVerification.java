package com.zsumz.logyard.tests.verification;

import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.api.diagnostics.ComponentHealth;
import com.zsumz.logyard.api.diagnostics.EffectiveRoute;
import com.zsumz.logyard.config.LogyardConfig;
import com.zsumz.logyard.runtime.assembly.LogyardRuntimeFactory;
import com.zsumz.logyard.tests.extensions.TestOutputProvider;

import java.util.List;

import static com.zsumz.logyard.tests.verification.VerificationAssertions.equal;
import static com.zsumz.logyard.tests.verification.VerificationAssertions.require;
import static com.zsumz.logyard.tests.verification.VerificationFixtures.extensionConfig;

final class ExtensionSpiVerification implements VerificationCase {
    @Override
    public String description() {
        return "custom formatter, encoder, processor, and output SPIs";
    }

    @Override
    public void verify() {
        TestOutputProvider.reset();
        LogyardConfig config = extensionConfig("allow = true", "marker = \"batch8-core\"", 16);
        LogyardRuntimeFactory.validate(config);
        String callerThread = Thread.currentThread().getName();
        try (LogyardRuntime runtime = LogyardRuntimeFactory.create(config)) {
            EffectiveRoute route = runtime.explain("tests.Custom");
            equal(VerificationAssertions.withDiscoveredContext(List.of("allow", "add")), route.processors());
            runtime.logger("tests.Custom").atInfo().log("hello");
            runtime.flush();
            ComponentHealth output = runtime.health().components().stream()
                    .filter(component -> "capture".equals(component.name()))
                    .findFirst()
                    .orElseThrow();
            equal("async", output.details().get("delivery"));
            equal("false", output.details().get("caller_thread_delivery"));
        }
        TestOutputProvider.Snapshot snapshot = TestOutputProvider.snapshot();
        equal("batch8-core", snapshot.marker());
        equal("capture", snapshot.outputName());
        equal("tests", snapshot.serviceName());
        equal("fmt:\\nhello", snapshot.formatted());
        equal("enc:hello", snapshot.encoded());
        equal(true, snapshot.enriched());
        require(!callerThread.equals(snapshot.threadName()), "custom output executed on the logging thread");
    }
}
