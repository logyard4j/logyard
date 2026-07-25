package com.zsumz.logyard.api.event;

import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BoundedMessageFormatTest {
    @Test
    void rendersValidMessageFormatNumberAndChoicePatterns() {
        BoundedMessageFormat.Result result = BoundedMessageFormat.messageFormat(
                "There {0,choice,0#are no files|1#is one file|1<are {0,number,integer} files}",
                new Object[] {42});

        assertEquals("There are 42 files", result.message());
        assertFalse(result.formatFailed());
    }

    @Test
    void rejectsInvalidMessageFormatWithoutLosingTheTemplate() {
        BoundedMessageFormat.Result result = BoundedMessageFormat.messageFormat("broken {0", new Object[] {"value"});

        assertEquals("broken {0", result.message());
        assertTrue(result.formatFailed());
    }

    @Test
    void boundsExtremeNumbersAndHostileObjects() {
        Object hostile = new Object() {
            @Override
            public String toString() {
                throw new IllegalStateException("no");
            }
        };
        BigDecimal extreme = new BigDecimal(BigInteger.ONE, -100_000_000);
        BigInteger huge = BigInteger.ONE.shiftLeft(1_000_000);

        BoundedMessageFormat.Result result =
                BoundedMessageFormat.messageFormat("{0} {1} {2}", new Object[] {extreme, huge, hostile});

        assertTrue(result.message().contains("1E+100000000"));
        assertTrue(result.message().contains("numeric value omitted"));
        assertTrue(result.message().contains("FAILED toString()"));
        assertTrue(result.message().length() <= CaptureLimits.MAX_RENDERED_MESSAGE_CHARS);
    }

    @Test
    void boundsHugeTemplatesAndPrintfFields() {
        BoundedMessageFormat.Result messageFormat =
                BoundedMessageFormat.messageFormat("{0}".repeat(100_000), new Object[] {"value"});
        BoundedMessageFormat.Result printf =
                BoundedMessageFormat.printf("%100000000s", new Object[] {"value"});

        assertTrue(messageFormat.template().length() <= CaptureLimits.MAX_EVENT_TEMPLATE_CHARS);
        assertTrue(messageFormat.message().length() <= CaptureLimits.MAX_RENDERED_MESSAGE_CHARS);
        assertTrue(messageFormat.truncated());
        assertTrue(printf.message().length() <= CaptureLimits.MAX_CAPTURED_NUMBER_CHARS);
        assertTrue(printf.truncated());
    }

    @Test
    void rejectsRepeatedMessageFormatExpansionBeforeTheJdkFormatterAllocatesIt() {
        String pattern = "{0}".repeat(CaptureLimits.MAX_EVENT_TEMPLATE_CHARS / 3);

        BoundedMessageFormat.Result result =
                BoundedMessageFormat.messageFormat(pattern, new Object[] {"x".repeat(CaptureLimits.MAX_CAPTURED_NUMBER_CHARS)});

        assertTrue(result.message().contains("format expansion omitted"));
        assertTrue(result.truncated());
        assertFalse(result.formatFailed());
    }

    @Test
    void rejectsQuotedChoiceExpansionBeforeMessageFormatRecursivelyParsesIt() {
        String pattern = "{0,choice,0#" + "'{1}'".repeat(1_600) + "}";

        BoundedMessageFormat.Result result =
                BoundedMessageFormat.messageFormat(pattern, new Object[] {0, "x".repeat(CaptureLimits.MAX_CAPTURED_NUMBER_CHARS)});

        assertTrue(result.message().contains("format expansion omitted"));
        assertTrue(result.truncated());
        assertFalse(result.formatFailed());
    }

    @Test
    void leavesUnreferencedMessageFormatAndPrintfArgumentsUntouched() {
        AtomicInteger renderCalls = new AtomicInteger();
        Object unreferenced = new Object() {
            @Override
            public String toString() {
                renderCalls.incrementAndGet();
                return "should not render";
            }
        };

        BoundedMessageFormat.Result messageFormat =
                BoundedMessageFormat.messageFormat("selected {1}", new Object[] {unreferenced, "message"});
        BoundedMessageFormat.Result printf =
                BoundedMessageFormat.printf("selected %2$s %<s", new Object[] {unreferenced, "message"});
        BoundedMessageFormat.printf("literal %% %n", new Object[] {unreferenced});

        assertEquals("selected message", messageFormat.message());
        assertEquals("selected message message", printf.message());
        assertEquals(0, renderCalls.get());
    }

    @Test
    void streamsRepeatedPrintfExpansionDirectlyIntoTheOutputAllowance() {
        String pattern = "%1$s".repeat(CaptureLimits.MAX_EVENT_TEMPLATE_CHARS / 4);

        BoundedMessageFormat.Result result =
                BoundedMessageFormat.printf(pattern, new Object[] {"x".repeat(CaptureLimits.MAX_CAPTURED_NUMBER_CHARS)});

        assertEquals(CaptureLimits.MAX_RENDERED_MESSAGE_CHARS, result.message().length());
        assertTrue(result.message().endsWith("…"));
        assertTrue(result.truncated());
    }

    @Test
    void repeatedMessageFormattingStaysWithinAOneMegabyteAllocationEnvelope() {
        java.lang.management.ThreadMXBean platformBean = ManagementFactory.getThreadMXBean();
        if (!(platformBean instanceof com.sun.management.ThreadMXBean allocationBean)
                || !allocationBean.isThreadAllocatedMemorySupported()) {
            return;
        }
        allocationBean.setThreadAllocatedMemoryEnabled(true);
        String pattern = "{0}".repeat(CaptureLimits.MAX_EVENT_TEMPLATE_CHARS / 3);
        Object[] parameters = {"x".repeat(CaptureLimits.MAX_CAPTURED_NUMBER_CHARS)};
        BoundedMessageFormat.messageFormat(pattern, parameters);

        long before = allocationBean.getThreadAllocatedBytes(Thread.currentThread().threadId());
        BoundedMessageFormat.messageFormat(pattern, parameters);
        long allocated = allocationBean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before;

        assertTrue(allocated < 1_000_000L, () -> "repeated MessageFormat allocated " + allocated + " bytes");
    }

    @Test
    void quotedChoiceFormattingStaysWithinAOneMegabyteAllocationEnvelope() {
        java.lang.management.ThreadMXBean platformBean = ManagementFactory.getThreadMXBean();
        if (!(platformBean instanceof com.sun.management.ThreadMXBean allocationBean)
                || !allocationBean.isThreadAllocatedMemorySupported()) {
            return;
        }
        allocationBean.setThreadAllocatedMemoryEnabled(true);
        String pattern = "{0,choice,0#" + "'{1}'".repeat(1_600) + "}";
        Object[] parameters = {0, "x".repeat(CaptureLimits.MAX_CAPTURED_NUMBER_CHARS)};
        BoundedMessageFormat.messageFormat(pattern, parameters);

        long before = allocationBean.getThreadAllocatedBytes(Thread.currentThread().threadId());
        BoundedMessageFormat.messageFormat(pattern, parameters);
        long allocated = allocationBean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before;

        assertTrue(allocated < 1_000_000L, () -> "quoted ChoiceFormat allocated " + allocated + " bytes");
    }
}
