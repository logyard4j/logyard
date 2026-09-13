package com.logyard4j.logyard.core.runtime.publication;

import com.logyard4j.logyard.api.event.CaptureLimits;
import com.logyard4j.logyard.api.event.LogEvent;
import com.logyard4j.logyard.api.event.SystemAttributes;
import com.logyard4j.logyard.core.runtime.DefaultLogyardRuntime;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class PendingArgumentCaptureTest {
    @Test
    void directSupplierObjectsAndDeferredResultsShareOneDetachedCapture() {
        List<LogEvent> events = new ArrayList<>();
        AtomicInteger evaluations = new AtomicInteger();
        AtomicInteger renders = new AtomicInteger();
        StringBuilder mutable = new StringBuilder("declared");
        Thread caller = Thread.currentThread();
        Supplier<Object> value = new Supplier<>() {
            @Override
            public Object get() {
                throw new AssertionError("direct supplier object evaluated");
            }

            @Override
            public String toString() {
                assertSame(caller, Thread.currentThread());
                renders.incrementAndGet();
                return mutable.toString();
            }
        };
        try (DefaultLogyardRuntime runtime = DefaultLogyardRuntime.consoleOnly(events::add)) {
            var builder = runtime.logger("test.Arguments").atInfo()
                    .argument(value)
                    .argumentLazy(() -> {
                        assertSame(caller, Thread.currentThread());
                        evaluations.incrementAndGet();
                        return value;
                    });
            assertEquals(0, evaluations.get());
            assertEquals(0, renders.get());
            mutable.replace(0, mutable.length(), "captured");
            builder.log("{} {} {}", value);
            mutable.replace(0, mutable.length(), "later");
        }

        assertEquals(1, events.size());
        assertArrayEquals(new Object[] {"captured", "captured", "captured"}, events.getFirst().arguments());
        assertEquals(1, evaluations.get());
        assertEquals(1, renders.get());
    }

    @Test
    void fullDeclarationsSkipLazyArgumentsAndAccountForLaterVarargs() {
        List<LogEvent> events = new ArrayList<>();
        try (DefaultLogyardRuntime runtime = DefaultLogyardRuntime.consoleOnly(events::add)) {
            var builder = runtime.logger("test.FullArguments").atInfo();
            for (int index = 0; index < CaptureLimits.MAX_ARGUMENTS; index++) {
                builder.argument(index);
            }
            builder.argumentLazy(() -> { throw new AssertionError("omitted supplier evaluated"); })
                    .log("arguments", "extra", "another");
        }

        LogEvent event = events.getFirst();
        assertEquals(CaptureLimits.MAX_ARGUMENTS, event.argumentCount());
        assertEquals(0, event.argumentAt(0));
        assertEquals(CaptureLimits.MAX_ARGUMENTS - 1, event.argumentAt(event.argumentCount() - 1));
        assertEquals(3, event.attributes().get(SystemAttributes.ARGUMENTS_OMITTED));
        assertEquals(true, event.attributes().get(SystemAttributes.CAPTURE_TRUNCATED));
    }
}
