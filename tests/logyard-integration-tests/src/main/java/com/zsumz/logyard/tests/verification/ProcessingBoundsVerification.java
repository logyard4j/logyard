package com.zsumz.logyard.tests.verification;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.core.processing.RateLimitProcessor;
import com.zsumz.logyard.core.processing.SamplingProcessor;

import static com.zsumz.logyard.tests.verification.VerificationAssertions.equal;
import static com.zsumz.logyard.tests.verification.VerificationAssertions.require;
import static com.zsumz.logyard.tests.verification.VerificationFixtures.event;

final class ProcessingBoundsVerification implements VerificationCase {
    @Override
    public String description() {
        return "bounded sampling and rate limiting";
    }

    @Override
    public void verify() {
        LogEvent info = event(Level.INFO, "tests.Sample", "sample", "hello", null, AttributeSet.EMPTY);
        LogEvent warn = event(Level.WARN, "tests.Sample", "sample", "warn", null, AttributeSet.EMPTY);
        SamplingProcessor dropAll = new SamplingProcessor(0.0d, "logger", 17L);
        equal(null, dropAll.process(info));
        require(dropAll.process(warn) == warn, "WARN must bypass sampling");

        SamplingProcessor deterministic = new SamplingProcessor(0.5d, "event", 99L);
        boolean first = deterministic.process(info) != null;
        boolean second = deterministic.process(info) != null;
        equal(first, second);

        RateLimitProcessor limiter = new RateLimitProcessor(0.001d, 1, "logger", 2);
        require(limiter.process(info) == info, "first rate-limit token should pass");
        equal(null, limiter.process(info));
        limiter.process(event(Level.INFO, "tests.Other", "sample", "two", null, AttributeSet.EMPTY));
        limiter.process(event(Level.INFO, "tests.Third", "sample", "three", null, AttributeSet.EMPTY));
        require(limiter.trackedKeys() <= 2, "rate limiter exceeded key bound");
        require(limiter.process(warn) == warn, "WARN must bypass rate limiting");
    }
}
