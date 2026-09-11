package com.logyard4j.api;

import com.logyard4j.api.event.AttributeSet;
import com.logyard4j.api.event.CaptureLimits;
import com.logyard4j.api.event.SystemAttributes;

import java.util.Arrays;

final class LoggerConvenience {
    private LoggerConvenience() {
    }

    static void log(LogyardLogger logger, Level level, String message) {
        if (logger.isEnabled(level)) {
            logger.log(level, null, message, null, AttributeSet.EMPTY, null);
        }
    }

    static void log(LogyardLogger logger, Level level, String message, Object argument) {
        if (logger.isEnabled(level)) {
            logger.log(level, null, message, new Object[] {argument}, AttributeSet.EMPTY, null);
        }
    }

    static void log(LogyardLogger logger, Level level, String message, Object first, Object second) {
        if (logger.isEnabled(level)) {
            if (second instanceof Throwable throwable) {
                logger.log(level, null, message, new Object[] {first}, AttributeSet.EMPTY, throwable);
            } else {
                logger.log(level, null, message, new Object[] {first, second}, AttributeSet.EMPTY, null);
            }
        }
    }

    static void log(LogyardLogger logger, Level level, String message, Throwable throwable) {
        if (logger.isEnabled(level)) {
            logger.log(level, null, message, null, AttributeSet.EMPTY, throwable);
        }
    }

    static void log(LogyardLogger logger, Level level, String message, Object[] arguments) {
        if (!logger.isEnabled(level)) {
            return;
        }
        Throwable throwable = null;
        Object[] actual = arguments;
        AttributeSet attributes = AttributeSet.EMPTY;
        if (arguments != null && arguments.length > 0 && arguments[arguments.length - 1] instanceof Throwable candidate) {
            throwable = candidate;
            int supplied = arguments.length - 1;
            int retained = Math.min(supplied, CaptureLimits.MAX_ARGUMENTS);
            actual = Arrays.copyOf(arguments, retained);
            if (supplied > retained) {
                attributes = AttributeSet.systemBuilder(1).put(SystemAttributes.ARGUMENTS_OMITTED, supplied - retained).build();
            }
        }
        logger.log(level, null, message, actual, attributes, throwable);
    }
}
