package com.logyard4j.tests.verification;

import com.logyard4j.api.diagnostics.EffectiveRoute;
import com.logyard4j.config.LogyardConfig;
import com.logyard4j.runtime.assembly.LogyardRuntimeFactory;

import java.util.List;

import static com.logyard4j.tests.verification.VerificationAssertions.equal;
import static com.logyard4j.tests.verification.VerificationFixtures.parse;

final class ProcessorInheritanceVerification implements VerificationCase {
    @Override
    public String description() {
        return "independent filter and enricher inheritance";
    }

    @Override
    public void verify() {
        LogyardConfig config = parse("""
                schema = 1
                [filters.sample]
                type = "sampling"
                probability = 1.0
                key = "logger"
                [enrichers.add]
                provider = "test-enricher"
                [enrichers.add.config]
                attribute = "verified"
                [loggers]
                root = { outputs = ["console"], filters = ["sample"] }
                "tests" = { enrich = ["add"] }
                [outputs.console]
                type = "console"
                color = { mode = "never" }
                """);
        EffectiveRoute route = LogyardRuntimeFactory.explain(config, "tests.Child");
        equal(VerificationAssertions.withDiscoveredContext(List.of("sample", "add")), route.processors());
    }
}
