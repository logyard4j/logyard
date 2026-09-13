package com.logyard4j.output.json.encoding;

/** Exact character accounting for signed decimal integers, including {@link Long#MIN_VALUE}. */
final class JsonDecimal {
    private JsonDecimal() {
    }

    static int length(long number) {
        int length = number < 0 ? 2 : 1;
        for (long remaining = number; remaining <= -10 || remaining >= 10; remaining /= 10) {
            length++;
        }
        return length;
    }
}
