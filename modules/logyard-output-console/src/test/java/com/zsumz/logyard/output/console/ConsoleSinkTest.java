package com.zsumz.logyard.output.console;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.CaptureLimits;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.formatting.TextFormatter;
import com.zsumz.logyard.output.console.style.BuiltInThemes;
import com.zsumz.logyard.output.console.terminal.ColorCapability;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ConsoleSinkTest {
    @Test
    void escapesControlCharactersAndCanRenderWithoutAnsi() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ConsoleSink sink = sink(bytes, true, false, null);
        sink.accept(event(
                "hello {}",
                new Object[] {"world\u001b[31m"},
                AttributeSet.of("request.id", "a\nb"),
                null));
        String rendered = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("hello world\\u001b[31m"));
        assertTrue(rendered.contains("request.id=a\\nb"));
        assertFalse(rendered.contains("\u001b["));
    }

    @Test
    void adaptsCustomFormattersToOneSanitizedConsoleRecord() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ConsoleSink sink = sink(bytes, true, false, ignored -> "custom\nrecord");

        sink.accept(event("ignored", null, AttributeSet.EMPTY, null));

        assertEquals("custom\\nrecord\n", bytes.toString(StandardCharsets.UTF_8));
        assertEquals("custom-text", sink.health("console").details().get("format"));
    }

    @Test
    void rendersSuppressedCausesAndCollapsesCommonFrames() {
        StackTraceElement common = new StackTraceElement("com.acme.Worker", "run", "Worker.java", 42);
        IllegalArgumentException cause = new IllegalArgumentException("cause");
        cause.setStackTrace(new StackTraceElement[] {common});
        IllegalStateException failure = new IllegalStateException("outer", cause);
        failure.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("com.acme.Service", "call", "Service.java", 10),
                common
        });
        failure.addSuppressed(new IllegalStateException("suppressed"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ConsoleSink sink = sink(bytes, false, true, null);

        sink.accept(event("failed", null, AttributeSet.EMPTY, failure));

        String rendered = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("Suppressed: java.lang.IllegalStateException: suppressed"));
        assertTrue(rendered.contains("Caused by: java.lang.IllegalArgumentException: cause"));
        assertTrue(rendered.contains("1 common frame(s)"), rendered);
    }

    @Test
    void boundsTheCompletePhysicalEventAcrossPayloadAndExceptionLines() {
        String controls = "\u0000".repeat(CaptureLimits.MAX_EVENT_PAYLOAD_TEXT_CHARS);
        IllegalStateException failure = new IllegalStateException(
                "\u0000".repeat(CaptureLimits.MAX_EVENT_EXCEPTION_MESSAGE_CHARS));
        StackTraceElement[] frames = new StackTraceElement[256];
        for (int index = 0; index < frames.length; index++) {
            frames[index] = new StackTraceElement(
                    "example.Service" + index,
                    "operation" + index,
                    "Service.java",
                    index);
        }
        failure.setStackTrace(frames);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ConsoleSink sink = sink(bytes, false, false, null);

        sink.accept(event("{}", new Object[] {controls}, AttributeSet.of("payload", controls), failure));

        String rendered = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.length() <= 131_072);
        assertTrue(rendered.contains("java.lang.IllegalStateException"));
        assertFalse(rendered.contains("\u0000"));
    }

    @Test
    void customFormatterCanInvokeAcceptFlushAndCloseAcrossThreadsWithoutDeadlock() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ExecutorService callbacks = Executors.newSingleThreadExecutor();
        AtomicReference<ConsoleSink> sinkReference = new AtomicReference<>();
        AtomicBoolean outer = new AtomicBoolean(true);
        TextFormatter formatter = event -> {
            if (outer.compareAndSet(true, false)) {
                invoke(callbacks, () -> sinkReference.get().accept(event("nested", null, AttributeSet.EMPTY, null)));
                invoke(callbacks, () -> sinkReference.get().flush());
                invoke(callbacks, () -> sinkReference.get().close());
            }
            return event.messageTemplate();
        };
        ConsoleSink sink = sink(bytes, true, false, formatter);
        sinkReference.set(sink);
        try {
            assertThrows(IllegalStateException.class, () -> sink.accept(event("outer", null, AttributeSet.EMPTY, null)));
        } finally {
            callbacks.shutdownNow();
        }
        assertEquals("nested\n", bytes.toString(StandardCharsets.UTF_8));
    }

    private static ConsoleSink sink(
            ByteArrayOutputStream bytes,
            boolean compactExceptions,
            boolean collapseCommonFrames,
            TextFormatter formatter) {
        return new ConsoleSink(
                new PrintStream(bytes, true, StandardCharsets.UTF_8),
                false,
                BuiltInThemes.ember(),
                ColorCapability.TRUECOLOR,
                ZoneOffset.UTC,
                compactExceptions,
                collapseCommonFrames,
                false,
                formatter);
    }

    private static LogEvent event(String template, Object[] arguments, AttributeSet attributes, Throwable exception) {
        return new LogEvent(
                0,
                0,
                Level.INFO,
                "com.acme.Service",
                "order.accepted",
                template,
                arguments,
                attributes,
                exception,
                1,
                "main");
    }

    private static void invoke(ExecutorService executor, Runnable operation) {
        try {
            executor.submit(operation).get(2L, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new AssertionError("cross-thread sink operation did not complete", failure);
        }
    }
}
