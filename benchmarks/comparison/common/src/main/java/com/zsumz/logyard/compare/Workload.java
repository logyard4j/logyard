package com.zsumz.logyard.compare;

import org.slf4j.Logger;
import org.slf4j.MDC;

import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/** Prepares identities before timing; each recorded call still uses the real SLF4J provider. */
public final class Workload {
    public static final String BARRIER = "comparison-drain-barrier";
    private static final Object[] VALUES = {42L, "customer-7", true, 9.5d};
    private final RunOptions options;
    private final String[] templates;
    private final LongAdder supplierCalls = new LongAdder();
    private final Supplier<Object> supplier = () -> {
        supplierCalls.increment();
        return "unexpected";
    };

    public Workload(RunOptions options) {
        this.options = options;
        String message = switch (options.arguments()) {
            case 0 -> "accepted order";
            case 1 -> "accepted order {}";
            case 2 -> "accepted order {} for {}";
            case 4 -> "accepted {} {} {} {}";
            default -> throw new IllegalArgumentException("unsupported argument count");
        };
        if (!options.format().equals("text")) message += " \"escaped\"\\path\t\nλ";
        templates = new String[options.events()];
        for (int index = 0; index < templates.length; index++) {
            String identity = Integer.toHexString(index);
            templates[index] = "0".repeat(8 - identity.length()) + identity + ' ' + message;
        }
    }

    public void prepareThread() {
        for (String key : options.fieldNames()) MDC.put(key, key + "-value\"\\\tλ");
    }

    public void log(Logger logger, int index) {
        if (!options.enabled()) {
            switch (options.disabled()) {
                case "classic" -> logger.debug(templates[index], VALUES[0]);
                case "fluent" -> logger.atDebug().addArgument(VALUES[0]).log(templates[index]);
                case "supplier" -> logger.atDebug().addArgument(supplier).log(templates[index]);
                default -> throw new IllegalStateException("unknown disabled workload");
            }
            return;
        }
        if (index % 8 == 0) {
            switch (options.arguments()) {
                case 0 -> logger.error(templates[index]);
                case 1 -> logger.error(templates[index], VALUES[0]);
                case 2 -> logger.error(templates[index], VALUES[0], VALUES[1]);
                case 4 -> logger.error(templates[index], VALUES);
                default -> throw new IllegalStateException("unknown argument count");
            }
        } else {
            switch (options.arguments()) {
                case 0 -> logger.info(templates[index]);
                case 1 -> logger.info(templates[index], VALUES[0]);
                case 2 -> logger.info(templates[index], VALUES[0], VALUES[1]);
                case 4 -> logger.info(templates[index], VALUES);
                default -> throw new IllegalStateException("unknown argument count");
            }
        }
    }

    public long supplierCalls() {
        return supplierCalls.sum();
    }
}
