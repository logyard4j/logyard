package com.logyard4j.api.event;

import com.logyard4j.api.Level;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class LogEventCaptureScopeTest {
    enum InnerOutcome { SUCCESS, FAILURE, FATAL }

    @ParameterizedTest
    @EnumSource(InnerOutcome.class)
    void nestedCaptureRestoresTheOuterBudgetAndSharedValueIdentity(InnerOutcome outcome) {
        AtomicInteger renders = new AtomicInteger();
        StringBuilder text = new StringBuilder("captured");
        Object shared = new Object() {
            @Override
            public String toString() {
                renders.incrementAndGet();
                return text.toString();
            }
        };

        LogEvent outer = event(() -> new Object[] {shared}, () -> {
            Supplier<LogEvent> inner = () -> event(
                    () -> new Object[] {"p".repeat(CaptureLimits.MAX_EVENT_PAYLOAD_TEXT_CHARS + 1)},
                    () -> switch (outcome) {
                        case SUCCESS -> AttributeSet.EMPTY;
                        case FAILURE -> throw new IllegalStateException("inner capture failed");
                        case FATAL -> throw new CaptureFailure();
                    });
            switch (outcome) {
                case SUCCESS -> assertEquals(true, inner.get().attributes().get(SystemAttributes.CAPTURE_TRUNCATED));
                case FAILURE -> assertThrows(IllegalStateException.class, inner::get);
                case FATAL -> assertThrows(CaptureFailure.class, inner::get);
            }
            return AttributeSet.builder().put("shared", shared).put("tail", "available").build();
        });
        text.replace(0, text.length(), "changed");

        assertEquals(1, renders.get());
        assertEquals("captured", outer.argumentAt(0));
        assertEquals("captured", outer.attributes().get("shared"));
        assertEquals("available", outer.attributes().get("tail"));
        assertEquals(2, outer.attributes().size());
        assertNull(outer.attributes().get(SystemAttributes.CAPTURE_TRUNCATED));
    }

    private static LogEvent event(Supplier<Object[]> arguments, Supplier<AttributeSet> attributes) {
        return LogEvent.captureDeferred(1L, 2L, Level.INFO, "test.scope", null, "{}",
                arguments, attributes, 1, null, 3L, "test");
    }

    private static final class CaptureFailure extends VirtualMachineError {
        private static final long serialVersionUID = 1L;
    }
}
