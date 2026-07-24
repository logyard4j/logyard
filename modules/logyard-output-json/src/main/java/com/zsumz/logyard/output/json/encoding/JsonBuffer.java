package com.zsumz.logyard.output.json.encoding;

/** Reusable hard-bounded JSON character buffer that releases unusually large backing arrays. */
final class JsonBuffer {
    private static final int MIN_RETAINED_CAPACITY = 4_096;

    private final int initialCapacity;
    private final int maximumCharacters;
    private StringBuilder value;

    JsonBuffer(int initialCapacity, int maximumCharacters) {
        this.initialCapacity = initialCapacity;
        this.maximumCharacters = maximumCharacters;
        value = new StringBuilder(initialCapacity);
    }

    void reset() {
        if (value.capacity() > Math.max(MIN_RETAINED_CAPACITY, initialCapacity * 4)) {
            value = new StringBuilder(initialCapacity);
        } else {
            value.setLength(0);
        }
    }

    void append(char character) {
        require(1);
        value.append(character);
    }

    void append(CharSequence text) {
        require(text.length());
        value.append(text);
    }

    void append(long number) {
        append(Long.toString(number));
    }

    void append(boolean flag) {
        append(Boolean.toString(flag));
    }

    int length() {
        return value.length();
    }

    int capacity() {
        return value.capacity();
    }

    String result() {
        return value.toString();
    }

    private void require(int additional) {
        if (additional > maximumCharacters - value.length()) {
            throw JsonLimitExceeded.INSTANCE;
        }
    }
}
