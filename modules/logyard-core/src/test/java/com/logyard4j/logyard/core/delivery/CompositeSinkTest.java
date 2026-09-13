package com.logyard4j.logyard.core.delivery;

import com.logyard4j.logyard.api.spi.output.EventSink;
import com.logyard4j.logyard.core.failure.ComponentInvocationException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CompositeSinkTest {
    @Test
    void identifiesEveryFailedOutputWithoutChangingThePrimaryCause() {
        IllegalStateException consoleFailure = new IllegalStateException("console unavailable");
        IllegalArgumentException auditFailure = new IllegalArgumentException("audit unavailable");
        Map<String, EventSink> outputs = new LinkedHashMap<>();
        outputs.put("console", ignored -> {
            throw consoleFailure;
        });
        outputs.put("healthy", ignored -> { });
        outputs.put("audit-file", ignored -> {
            throw auditFailure;
        });

        ComponentInvocationException failure = assertThrows(
                ComponentInvocationException.class,
                () -> new CompositeSink(outputs).accept(null));

        assertTrue(failure.getMessage().contains("fanout output 'console' accept"));
        assertSame(consoleFailure, failure.getCause());
        assertEquals(1, failure.getSuppressed().length);
        assertTrue(failure.getSuppressed()[0].getMessage().contains("fanout output 'audit-file' accept"));
        assertSame(auditFailure, failure.getSuppressed()[0].getCause());
    }

    @Test
    void identifiesFlushFailuresByConfiguredOutputName() {
        EventSink failing = new EventSink() {
            @Override public void accept(com.logyard4j.logyard.api.event.LogEvent event) { }
            @Override public void flush() { throw new IllegalStateException("flush unavailable"); }
        };

        ComponentInvocationException failure = assertThrows(
                ComponentInvocationException.class,
                () -> new CompositeSink(Map.of("audit-file", failing)).flush());

        assertTrue(failure.getMessage().contains("fanout output 'audit-file' flush"));
    }
}
