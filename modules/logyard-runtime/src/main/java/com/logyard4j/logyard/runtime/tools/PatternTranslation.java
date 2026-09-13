package com.logyard4j.logyard.runtime.tools;

import java.util.List;

/** A rendered draft plus explicit formatting losses and unsupported source conversions. */
record PatternTranslation(String template, List<String> losses, List<String> unsupported) {
    PatternTranslation {
        losses = List.copyOf(losses);
        unsupported = List.copyOf(unsupported);
    }

    void report(String appender, LogbackModel model) {
        for (String loss : losses) model.note("appender '" + appender + "': " + loss);
        for (String failure : unsupported) model.unsupported("appender '" + appender + "': " + failure);
    }
}
