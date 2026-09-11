package com.logyard4j.output.json.flush;

import java.time.Duration;

/** Controller-confined bounded backoff for retrying rejected flush dispatches. */
final class FlushDispatchRetry {
    private static final Duration FIRST_DELAY = Duration.ofMillis(10L);
    private static final Duration SECOND_DELAY = Duration.ofMillis(50L);
    private static final Duration THIRD_DELAY = Duration.ofMillis(250L);
    private static final Duration MAXIMUM_DELAY = Duration.ofSeconds(1L);

    private int delayIndex;

    Duration nextDelay() {
        Duration delay = switch (delayIndex) {
            case 0 -> FIRST_DELAY;
            case 1 -> SECOND_DELAY;
            case 2 -> THIRD_DELAY;
            default -> MAXIMUM_DELAY;
        };
        if (delayIndex < 3) {
            delayIndex++;
        }
        return delay;
    }

    void reset() {
        delayIndex = 0;
    }
}
