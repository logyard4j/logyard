package com.logyard4j.logyard.api.event;

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
    void treatsArbitraryPrecisionNumberSubclassesAsCallerOwnedObjects() {
        BigInteger integer = new BigInteger("42") {
            @Override public int bitLength() { throw new AssertionError("subclass bitLength must not be trusted"); }
            @Override public String toString() { return "integer-subclass"; }
        };
        BigDecimal decimal = new BigDecimal("42") {
            @Override public int precision() { throw new AssertionError("subclass precision must not be trusted"); }
            @Override public String toString() { return "decimal-subclass"; }
        };

        BoundedMessageFormat.Result result =
                BoundedMessageFormat.messageFormat("{0} {1}", new Object[] {integer, decimal});

        assertEquals("integer-subclass decimal-subclass", result.message());
        assertFalse(result.formatFailed());
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
        BoundedMessageFormat.printf("indexed literals %1$% %1$n", new Object[] {unreferenced});

        assertEquals("selected message", messageFormat.message());
        assertEquals("selected message message", printf.message());
        assertEquals(0, renderCalls.get());
    }

    @Test
    void leavesArgumentsInUnselectedChoiceBranchesUntouched() {
        AtomicInteger renderCalls = new AtomicInteger();
        Object unselected = new Object() {
            @Override
            public String toString() {
                renderCalls.incrementAndGet();
                return "should not render";
            }
        };

        BoundedMessageFormat.Result result = BoundedMessageFormat.messageFormat(
                "{0,choice,0#none|1#{1}}",
                new Object[] {0, unselected});

        assertEquals("none", result.message());
        assertEquals(0, renderCalls.get());
    }

    @Test
    void rejectsRepeatedDefaultNumberExpansionBeforeTheJdkFormatterAllocatesIt() {
        BigDecimal expanded = new BigDecimal(BigInteger.ONE, -2_048);
        String pattern = "{0}".repeat(490);

        BoundedMessageFormat.Result result =
                BoundedMessageFormat.messageFormat(pattern, new Object[] {expanded});

        assertTrue(result.message().contains("format expansion omitted"));
        assertTrue(result.truncated());
        assertFalse(result.formatFailed());
    }

    @Test
    void boundsEveryBuiltInNumberFormatKindBeforeRendering() {
        Object[] values = {
                new BigDecimal(BigInteger.ONE, -2_048),
                BigInteger.TEN.pow(2_000),
                Double.MAX_VALUE
        };
        String[] elements = {
                "{0}",
                "{0,number}",
                "{0,number,integer}",
                "{0,number,currency}",
                "{0,number,percent}",
                "{0,number,#,##0.00}"
        };

        for (Object value : values) {
            for (String element : elements) {
                BoundedMessageFormat.Result result =
                        BoundedMessageFormat.messageFormat(element.repeat(100), new Object[] {value});
                assertTrue(
                        result.message().contains("format expansion omitted"),
                        () -> "format work was not bounded for " + element + " and " + value.getClass().getSimpleName());
            }
        }
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

    @Test
    void repeatedDefaultNumberFormattingStaysWithinAOneMegabyteAllocationEnvelope() {
        java.lang.management.ThreadMXBean platformBean = ManagementFactory.getThreadMXBean();
        if (!(platformBean instanceof com.sun.management.ThreadMXBean allocationBean)
                || !allocationBean.isThreadAllocatedMemorySupported()) {
            return;
        }
        allocationBean.setThreadAllocatedMemoryEnabled(true);
        String pattern = "{0}".repeat(490);
        Object[] parameters = {new BigDecimal(BigInteger.ONE, -2_048)};
        BoundedMessageFormat.messageFormat(pattern, parameters);

        long before = allocationBean.getThreadAllocatedBytes(Thread.currentThread().threadId());
        BoundedMessageFormat.messageFormat(pattern, parameters);
        long allocated = allocationBean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before;

        assertTrue(allocated < 1_000_000L, () -> "default number MessageFormat allocated " + allocated + " bytes");
    }
}
