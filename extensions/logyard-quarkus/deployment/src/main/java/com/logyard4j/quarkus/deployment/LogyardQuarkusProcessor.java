package com.logyard4j.quarkus.deployment;

import com.logyard4j.quarkus.runtime.LogyardQuarkusRecorder;
import com.logyard4j.quarkus.runtime.health.LogyardReadinessCheck;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.Capability;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.LogHandlerBuildItem;
import io.quarkus.deployment.builditem.ShutdownContextBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourcePatternsBuildItem;

/** Quarkus build steps for the Logyard logging handler. */
final class LogyardQuarkusProcessor {
    private static final String FEATURE = "logyard";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    NativeImageResourcePatternsBuildItem tomlResources() {
        return NativeImageResourcePatternsBuildItem.builder()
                .includeGlob("logyard.toml")
                .includeGlob("logyard-*.toml")
                .includeGlob("**/logyard.toml")
                .includeGlob("**/logyard-*.toml")
                .build();
    }

    @BuildStep
    void health(Capabilities capabilities, BuildProducer<AdditionalBeanBuildItem> beans) {
        if (capabilities.isPresent(Capability.SMALLRYE_HEALTH)) {
            beans.produce(AdditionalBeanBuildItem.unremovableOf(LogyardReadinessCheck.class.getName()));
        }
    }

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    LogHandlerBuildItem handler(
            LogyardQuarkusRecorder recorder,
            ShutdownContextBuildItem shutdown) {
        return new LogHandlerBuildItem(recorder.initialize(shutdown));
    }
}
