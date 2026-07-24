package com.zsumz.logyard.runtime.reload;

import com.zsumz.logyard.api.reload.ReloadResult;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

final class ReloadDebouncer {
    private final long delayNanos;
    private final LongSupplier nanoTime;
    private long deadline = Long.MAX_VALUE;

    ReloadDebouncer(Duration delay) {
        this(delay, System::nanoTime);
    }

    ReloadDebouncer(Duration delay, LongSupplier nanoTime) {
        delayNanos = saturatedNanos(Objects.requireNonNull(delay, "delay"));
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    void signalChange() {
        long now = nanoTime.getAsLong();
        long candidate = now + delayNanos;
        deadline = candidate < 0L && now > 0L ? Long.MAX_VALUE - 1L : candidate;
    }

    void runIfDue(Supplier<ReloadResult> reload) {
        Objects.requireNonNull(reload, "reload");
        if (deadline != Long.MAX_VALUE && nanoTime.getAsLong() - deadline >= 0L) {
            deadline = Long.MAX_VALUE;
            if (Objects.requireNonNull(reload.get(), "reload result") == ReloadResult.REJECTED) {
                signalChange();
            }
        }
    }

    private static long saturatedNanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
