package com.logyard4j.logyard.api.event;

import com.logyard4j.logyard.api.context.LogContext;
import com.logyard4j.logyard.core.runtime.DefaultLogyardRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class FluentEmptyAttributesTest {
    enum Source { SCOPE, DECLARATION, PRESET }

    @ParameterizedTest
    @EnumSource(Source.class)
    void emptyAttributesKeepTheirCaptureTruncationProvenance(Source source) {
        AttributeSet truncated = new AttributeSet(new String[0], null, new Object[0], true);
        List<LogEvent> events = new ArrayList<>();
        try (DefaultLogyardRuntime runtime = DefaultLogyardRuntime.consoleOnly(events::add)) {
            var logger = runtime.logger("test.EmptyAttributes");
            switch (source) {
                case SCOPE -> {
                    try (var ignored = LogContext.push(truncated)) {
                        logger.atInfo().log("empty");
                    }
                }
                case DECLARATION -> logger.atInfo().addAll(truncated).log("empty");
                case PRESET -> logger.with(truncated).atInfo().log("empty");
            }
        }

        assertEquals(1, events.size());
        assertEquals(1, events.getFirst().attributes().size());
        assertEquals(true, events.getFirst().attributes().get(SystemAttributes.CAPTURE_TRUNCATED));
    }

    @Test
    void ordinaryEmptyAttributesKeepTheArgumentCaptureTruncationMarker() {
        List<LogEvent> events = new ArrayList<>();
        try (DefaultLogyardRuntime runtime = DefaultLogyardRuntime.consoleOnly(events::add)) {
            runtime.logger("test.EmptyAttributes").atInfo()
                    .argument("p".repeat(CaptureLimits.MAX_EVENT_PAYLOAD_TEXT_CHARS + 1))
                    .log("{}");
        }

        LogEvent event = events.getFirst();
        assertEquals(1, event.argumentCount());
        assertEquals(CaptureLimits.MAX_EVENT_PAYLOAD_TEXT_CHARS, ((String) event.argumentAt(0)).length());
        assertEquals(true, event.attributes().get(SystemAttributes.CAPTURE_TRUNCATED));
    }
}
