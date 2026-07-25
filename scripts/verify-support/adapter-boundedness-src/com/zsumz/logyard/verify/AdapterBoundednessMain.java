package com.zsumz.logyard.verify;

import com.zsumz.logyard.api.event.CaptureLimits;
import com.zsumz.logyard.api.event.BoundedMessageFormat;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.core.runtime.DefaultLogyardRuntime;
import com.zsumz.logyard.jul.LogyardHandler;
import com.zsumz.logyard.runtime.adapter.BorrowedAdapterRuntime;
import com.zsumz.logyard.systemlogger.internal.factory.LogyardSystemLogger;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.LogRecord;

/** Constrained-heap probe that exercises the actual JUL and System.Logger adapter entry points. */
public final class AdapterBoundednessMain {
    private AdapterBoundednessMain() {
    }

    public static void main(String[] arguments) {
        List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());
        BigDecimal extremeDecimal = new BigDecimal(BigInteger.ONE, -100_000_000);
        BigInteger hugeInteger = BigInteger.ONE.shiftLeft(10_000_000);
        Object hostile = new Object() {
            @Override
            public String toString() {
                throw new IllegalStateException("hostile adapter parameter");
            }
        };

        HostileTimeZone hostileTimeZone = new HostileTimeZone();
        try (DefaultLogyardRuntime runtime = DefaultLogyardRuntime.consoleOnly(events::add)) {
            LogyardHandler handler = new LogyardHandler(runtime);
            LogRecord jul = new LogRecord(java.util.logging.Level.INFO, "{0} {1} {2}");
            jul.setLoggerName("boundedness.jul");
            jul.setParameters(new Object[] {extremeDecimal, hugeInteger, hostile});
            handler.publish(jul);

            System.Logger system = new LogyardSystemLogger(
                    "boundedness.system",
                    AdapterBoundednessMain.class.getModule(),
                    new BorrowedAdapterRuntime(runtime));
            system.log(System.Logger.Level.INFO, "{0}", extremeDecimal);
            system.log(System.Logger.Level.INFO, "x".repeat(100_000) + "{0}", "tail");

            LogRecord repeatedJul = new LogRecord(
                    java.util.logging.Level.INFO,
                    "{0}".repeat(CaptureLimits.MAX_EVENT_TEMPLATE_CHARS / 3));
            repeatedJul.setLoggerName("boundedness.jul.repeated");
            repeatedJul.setParameters(new Object[] {"x".repeat(CaptureLimits.MAX_CAPTURED_NUMBER_CHARS)});
            handler.publish(repeatedJul);
            system.log(
                    System.Logger.Level.INFO,
                    "{0}".repeat(CaptureLimits.MAX_EVENT_TEMPLATE_CHARS / 3),
                    "x".repeat(CaptureLimits.MAX_CAPTURED_NUMBER_CHARS));
            String recursiveChoice = "{0,choice,0#" + "'{1}'".repeat(1_600) + "}";
            LogRecord choiceJul = new LogRecord(java.util.logging.Level.INFO, recursiveChoice);
            choiceJul.setLoggerName("boundedness.jul.choice");
            choiceJul.setParameters(new Object[] {0, "x".repeat(CaptureLimits.MAX_CAPTURED_NUMBER_CHARS)});
            handler.publish(choiceJul);
            system.log(
                    System.Logger.Level.INFO,
                    recursiveChoice,
                    0,
                    "x".repeat(CaptureLimits.MAX_CAPTURED_NUMBER_CHARS));
            String repeatedDefaultNumber = "{0}".repeat(490);
            BigDecimal compactExpandedDecimal = new BigDecimal(BigInteger.ONE, -2_048);
            LogRecord numericJul = new LogRecord(java.util.logging.Level.INFO, repeatedDefaultNumber);
            numericJul.setLoggerName("boundedness.jul.number");
            numericJul.setParameters(new Object[] {compactExpandedDecimal});
            handler.publish(numericJul);
            system.log(System.Logger.Level.INFO, repeatedDefaultNumber, compactExpandedDecimal);
            String nestedTemporalPattern = "{0,date," + "[".repeat(8_000) + "u}";
            String expandingTemporalPattern = "{0,time," + "zzzz ".repeat(1_600) + "}";
            publishMessageFormat(handler, system, "boundedness.jul.temporal.nested", nestedTemporalPattern);
            publishMessageFormat(handler, system, "boundedness.jul.temporal.expanding", expandingTemporalPattern);
            TimeZone originalTimeZone = TimeZone.getDefault();
            TimeZone.setDefault(hostileTimeZone);
            try {
                LogRecord temporalJul = new LogRecord(java.util.logging.Level.INFO, "{0,time,full} {0,date,full}");
                temporalJul.setLoggerName("boundedness.jul.time");
                temporalJul.setParameters(new Object[] {new Date(0L)});
                handler.publish(temporalJul);
                system.log(System.Logger.Level.INFO, "{0,time,full} {0,date,full}", new Date(0L));
                BoundedMessageFormat.Result temporalPrintf =
                        BoundedMessageFormat.printf("%1$tZ %1$tc", new Object[] {new Date(0L)});
                BoundedMessageFormat.Result dateDisplay =
                        BoundedMessageFormat.printf("%s", new Object[] {new Date(0L)});
                require(!temporalPrintf.formatFailed(), "trusted temporal printf failed");
                require(!dateDisplay.formatFailed(), "trusted Date display failed");
                require(hostileTimeZone.cloneCalls.get() == 1, "adapter formatting cloned the application TimeZone");
                require(hostileTimeZone.behaviorCalls.get() == 0, "adapter formatting consulted the application TimeZone");
            } finally {
                TimeZone.setDefault(originalTimeZone);
            }
            verifyConcurrentRepeatedSubstitutions(handler, system);
            handler.close();
        }

        require(events.size() == 63, "adapter events were not delivered");
        for (LogEvent event : events) {
            require(event.renderedMessage().length() <= CaptureLimits.MAX_RENDERED_MESSAGE_CHARS, "adapter message exceeded its cap");
        }
        require(events.get(0).renderedMessage().contains("1E+100000000"), "JUL expanded an extreme decimal");
        require(events.get(0).renderedMessage().contains("numeric value omitted"), "JUL expanded a huge integer");
        require(events.get(0).renderedMessage().contains("FAILED toString()"), "JUL ran a hostile toString");
        require(events.get(1).renderedMessage().contains("1E+100000000"), "System.Logger expanded an extreme decimal");
        require(Boolean.TRUE.equals(events.get(2).attributes().get("logyard.capture.truncated")), "huge adapter template was not marked");
        require(events.get(3).renderedMessage().contains("format expansion omitted"), "JUL repeated expansion was not work-bounded");
        require(events.get(4).renderedMessage().contains("format expansion omitted"), "System.Logger repeated expansion was not work-bounded");
        require(events.get(5).renderedMessage().contains("format expansion omitted"), "JUL recursive choice was not work-bounded");
        require(events.get(6).renderedMessage().contains("format expansion omitted"), "System.Logger recursive choice was not work-bounded");
        require(events.get(7).renderedMessage().contains("format expansion omitted"), "JUL default number expansion was not work-bounded");
        require(events.get(8).renderedMessage().contains("format expansion omitted"), "System.Logger default number expansion was not work-bounded");
        require(events.get(9).renderedMessage().startsWith("{0,date,"), "JUL nested temporal pattern was not rejected");
        require(events.get(10).renderedMessage().startsWith("{0,date,"), "System.Logger nested temporal pattern was not rejected");
        require(events.get(11).renderedMessage().startsWith("{0,time,"), "JUL expanding temporal pattern was not rejected");
        require(events.get(12).renderedMessage().startsWith("{0,time,"), "System.Logger expanding temporal pattern was not rejected");
        require(events.get(13).renderedMessage().length() < 1_024, "JUL trusted time-zone rendering was not bounded");
        require(events.get(14).renderedMessage().length() < 1_024, "System.Logger trusted time-zone rendering was not bounded");
        System.out.println("Adapter boundedness verification passed under constrained heap");
    }

    private static void publishMessageFormat(
            LogyardHandler handler,
            System.Logger system,
            String loggerName,
            String pattern) {
        LogRecord record = new LogRecord(java.util.logging.Level.INFO, pattern);
        record.setLoggerName(loggerName);
        record.setParameters(new Object[] {new Date(0L)});
        handler.publish(record);
        system.log(System.Logger.Level.INFO, pattern, new Date(0L));
    }

    private static void verifyConcurrentRepeatedSubstitutions(LogyardHandler handler, System.Logger system) {
        int callerCount = 48;
        CountDownLatch ready = new CountDownLatch(callerCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> callers = new ArrayList<>(callerCount);
        for (int index = 0; index < callerCount; index++) {
            int callerIndex = index;
            Thread caller = new Thread(() -> {
                ready.countDown();
                await(start);
                try {
                    boolean numeric = (callerIndex & 2) != 0;
                    String pattern = numeric
                            ? "{0}".repeat(490)
                            : "{0}".repeat(CaptureLimits.MAX_EVENT_TEMPLATE_CHARS / 3);
                    Object parameter = numeric
                            ? new BigDecimal(BigInteger.ONE, -2_048)
                            : "x".repeat(CaptureLimits.MAX_CAPTURED_NUMBER_CHARS);
                    if ((callerIndex & 1) == 0) {
                        LogRecord record = new LogRecord(java.util.logging.Level.INFO, pattern);
                        record.setLoggerName("boundedness.jul.concurrent");
                        record.setParameters(new Object[] {parameter});
                        handler.publish(record);
                    } else {
                        system.log(System.Logger.Level.INFO, pattern, parameter);
                    }
                } catch (Throwable caught) {
                    failure.compareAndSet(null, caught);
                }
            }, "adapter-boundary-" + callerIndex);
            callers.add(caller);
            caller.start();
        }
        await(ready);
        start.countDown();
        for (Thread caller : callers) {
            join(caller);
        }
        require(failure.get() == null, "concurrent formatting failed: " + failure.get());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("adapter boundedness barrier interrupted", interrupted);
        }
    }

    private static void join(Thread thread) {
        try {
            thread.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("adapter boundedness caller interrupted", interrupted);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class HostileTimeZone extends TimeZone {
        private static final long serialVersionUID = 1L;

        private final AtomicInteger cloneCalls = new AtomicInteger();
        private final AtomicInteger behaviorCalls = new AtomicInteger();

        @Override
        public Object clone() {
            if (cloneCalls.incrementAndGet() > 1) {
                allocateWithoutBound();
            }
            return super.clone();
        }

        @Override
        public String getDisplayName(boolean daylight, int style, Locale locale) {
            behaviorCalls.incrementAndGet();
            allocateWithoutBound();
            return "unreachable";
        }

        @Override
        public int getOffset(long date) {
            behaviorCalls.incrementAndGet();
            allocateWithoutBound();
            return 0;
        }

        @Override
        public int getOffset(int era, int year, int month, int day, int dayOfWeek, int milliseconds) {
            behaviorCalls.incrementAndGet();
            allocateWithoutBound();
            return 0;
        }

        @Override public void setRawOffset(int offsetMillis) { }
        @Override public int getRawOffset() { behaviorCalls.incrementAndGet(); allocateWithoutBound(); return 0; }
        @Override public boolean useDaylightTime() { behaviorCalls.incrementAndGet(); allocateWithoutBound(); return false; }
        @Override public boolean inDaylightTime(Date date) { behaviorCalls.incrementAndGet(); allocateWithoutBound(); return false; }

        private static void allocateWithoutBound() {
            byte[] allocation = new byte[100_000_000];
            if (allocation.length == 0) {
                throw new AssertionError("unreachable");
            }
        }
    }
}
