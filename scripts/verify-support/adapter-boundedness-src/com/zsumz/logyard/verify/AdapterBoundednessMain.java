package com.zsumz.logyard.verify;

import com.zsumz.logyard.api.event.CaptureLimits;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.core.runtime.DefaultLogyardRuntime;
import com.zsumz.logyard.jul.LogyardHandler;
import com.zsumz.logyard.runtime.adapter.BorrowedAdapterRuntime;
import com.zsumz.logyard.systemlogger.internal.factory.LogyardSystemLogger;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.LogRecord;

/** Constrained-heap probe that exercises the actual JUL and System.Logger adapter entry points. */
public final class AdapterBoundednessMain {
    private AdapterBoundednessMain() {
    }

    public static void main(String[] arguments) {
        List<LogEvent> events = new ArrayList<>();
        BigDecimal extremeDecimal = new BigDecimal(BigInteger.ONE, -100_000_000);
        BigInteger hugeInteger = BigInteger.ONE.shiftLeft(10_000_000);
        Object hostile = new Object() {
            @Override
            public String toString() {
                throw new IllegalStateException("hostile adapter parameter");
            }
        };

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
            handler.close();
        }

        require(events.size() == 3, "adapter events were not delivered");
        for (LogEvent event : events) {
            require(event.renderedMessage().length() <= CaptureLimits.MAX_RENDERED_MESSAGE_CHARS, "adapter message exceeded its cap");
        }
        require(events.get(0).renderedMessage().contains("1E+100000000"), "JUL expanded an extreme decimal");
        require(events.get(0).renderedMessage().contains("numeric value omitted"), "JUL expanded a huge integer");
        require(events.get(0).renderedMessage().contains("FAILED toString()"), "JUL ran a hostile toString");
        require(events.get(1).renderedMessage().contains("1E+100000000"), "System.Logger expanded an extreme decimal");
        require(Boolean.TRUE.equals(events.get(2).attributes().get("logyard.capture.truncated")), "huge adapter template was not marked");
        System.out.println("Adapter boundedness verification passed under constrained heap");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
