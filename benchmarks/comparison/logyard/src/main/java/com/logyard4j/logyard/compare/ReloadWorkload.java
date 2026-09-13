package com.logyard4j.logyard.compare;

import org.slf4j.Logger;
import org.slf4j.MDC;

/** Identified records with fixed exception frames and detached MDC across configuration reloads. */
final class ReloadWorkload implements ProducerWorkload {
    private enum Phase { WARMUP, MEASUREMENT }
    private static final String MESSAGE = "accepted order {} for {} \"escaped\"\\path\t\nλ";
    private final RunOptions options;
    private final String[] templates;
    private final IllegalStateException failure = new IllegalStateException("representative failure");
    private Phase phase = Phase.WARMUP;

    ReloadWorkload(RunOptions options) {
        this.options = options;
        templates = new String[options.events()];
        for (int index = 0; index < templates.length; index++) {
            String identity = Integer.toHexString(index);
            templates[index] = "0".repeat(8 - identity.length()) + identity + ' ' + MESSAGE;
        }
        StackTraceElement[] frames = new StackTraceElement[8];
        for (int index = 0; index < frames.length; index++) {
            frames[index] = new StackTraceElement("com.example.orders.OrderService", "accept", "OrderService.java", 40 + index);
        }
        failure.setStackTrace(frames);
    }

    void beginMeasurement() {
        // ProducerTeam's start latch publishes this change after every producer finishes warmup.
        phase = Phase.MEASUREMENT;
    }

    @Override
    public void prepareThread() {
        for (String key : options.fieldNames()) MDC.put(key, key + "-value\"\\\tλ");
    }

    @Override
    public void log(Logger logger, int index) {
        String template = phase == Phase.WARMUP ? "warmup " + MESSAGE : templates[index];
        if (index % 8 == 0) logger.error(template, new Object[] {42L, "customer-7", failure});
        else logger.info(template, 42L, "customer-7");
    }
}
