package com.zsumz.logyard.runtime.assembly.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.config.logging.LoggerRuleConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ConfiguredLoggerRuleResolverTest {
    @Test
    void inheritsLoggerFieldsIndependently() {
        LoggerRuleConfig root = new LoggerRuleConfig(
                Level.INFO,
                List.of("console"),
                List.of("request-context"),
                List.of("sample"));
        Map<String, LoggerRuleConfig> rules = Map.of(
                "com.acme", new LoggerRuleConfig(Level.DEBUG, null, List.of("tenant"), null),
                "com.acme.noisy", new LoggerRuleConfig(null, List.of("json"), null, List.of("rate-limit")));

        ConfiguredLoggerRuleResolver.ResolvedRule resolved =
                ConfiguredLoggerRuleResolver.resolve("com.acme.noisy.Worker", root, rules);

        assertEquals(Level.DEBUG, resolved.rule().level());
        assertEquals(List.of("json"), resolved.rule().outputs());
        assertEquals(List.of("tenant"), resolved.rule().enrich());
        assertEquals(List.of("rate-limit"), resolved.rule().filters());
        assertEquals("com.acme.noisy", resolved.matchedRule());
    }
}
