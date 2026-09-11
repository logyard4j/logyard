package com.zsumz.logyard.examples.migration;

/** Real-provider XML fixtures keep excluded fields and distinct destinations visible. */
final class MigrationFixtures {
    private MigrationFixtures() { }

    static String single(String provider, String pattern, String target) {
        return configuration(provider, console(provider, "C", pattern, target, ""), reference(provider, "C"));
    }

    static String distinct(String provider, boolean async) {
        String appenders = console(provider, "AUDIT", "APPROVED %level %msg%n", "", "")
                + console(provider, "audit", "APPROVED %level %msg%n", "stderr", "WARN");
        if (async) {
            appenders += provider.equals("logback") ? """
                    <appender name="Async" class="ch.qos.logback.classic.AsyncAppender">
                      <appender-ref ref="AUDIT"/>
                    </appender>
                    """ : "<Async name=\"Async\"><AppenderRef ref=\"AUDIT\"/></Async>";
        }
        return configuration(provider, appenders, reference(provider, async ? "Async" : "AUDIT")
                + reference(provider, "audit"));
    }

    private static String console(String provider, String name, String pattern, String target, String threshold) {
        if (provider.equals("logback")) {
            return "<appender name=\"" + name + "\" class=\"ch.qos.logback.core.ConsoleAppender\">"
                    + (target.isEmpty() ? "" : "<target>System." + (target.equals("stderr") ? "err" : "out") + "</target>")
                    + "<encoder><pattern>" + pattern + "</pattern></encoder>"
                    + (threshold.isEmpty() ? "" : "<filter class=\"ch.qos.logback.classic.filter.ThresholdFilter\">"
                        + "<level>" + threshold + "</level></filter>") + "</appender>";
        }
        return "<Console name=\"" + name + "\""
                + (target.isEmpty() ? "" : " target=\"SYSTEM_" + (target.equals("stderr") ? "ERR" : "OUT") + "\"")
                + "><PatternLayout pattern=\"" + pattern + "\"/>"
                + (threshold.isEmpty() ? "" : "<ThresholdFilter level=\"" + threshold + "\"/>") + "</Console>";
    }

    private static String configuration(String provider, String appenders, String references) {
        return provider.equals("logback") ? "<configuration>" + appenders
                + "<root level=\"INFO\">" + references + "</root></configuration>"
                : "<Configuration><Appenders>" + appenders + "</Appenders><Loggers><Root level=\"INFO\">"
                + references + "</Root></Loggers></Configuration>";
    }

    private static String reference(String provider, String name) {
        return provider.equals("logback") ? "<appender-ref ref=\"" + name + "\"/>"
                : "<AppenderRef ref=\"" + name + "\"/>";
    }
}
