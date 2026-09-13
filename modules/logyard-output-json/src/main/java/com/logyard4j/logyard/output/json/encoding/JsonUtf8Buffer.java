package com.logyard4j.logyard.output.json.encoding;

import java.util.Arrays;

/** Reusable UTF-8 storage with the same character ceiling as text encoding and a hard byte ceiling. */
final class JsonUtf8Buffer implements JsonTokenBuffer {
    static final int MAX_RECORD_BYTES = 3 * JsonOutputLimits.MAX_RECORD_CHARACTERS;
    private static final int INITIAL_CAPACITY = 1_024;
    private static final int RETAINED_CAPACITY = 4_096;

    private byte[] bytes = new byte[INITIAL_CAPACITY];
    private int length;
    private int characters;

    @Override
    public void reset() {
        if (bytes.length > RETAINED_CAPACITY) {
            bytes = new byte[INITIAL_CAPACITY];
        }
        length = 0;
        characters = 0;
    }

    @Override
    public void append(char character) {
        if (characters == JsonOutputLimits.MAX_RECORD_CHARACTERS) {
            throw JsonLimitExceeded.INSTANCE;
        }
        if (Character.isSurrogate(character)) {
            throw new IllegalArgumentException("JSON surrogate code units must be escaped before UTF-8 encoding");
        }
        int count = character <= 0x7f ? 1 : character <= 0x7ff ? 2 : 3;
        reserve(count);
        if (count == 1) {
            bytes[length++] = (byte) character;
        } else if (count == 2) {
            bytes[length++] = (byte) (0xc0 | character >>> 6);
            bytes[length++] = (byte) (0x80 | character & 0x3f);
        } else {
            bytes[length++] = (byte) (0xe0 | character >>> 12);
            bytes[length++] = (byte) (0x80 | character >>> 6 & 0x3f);
            bytes[length++] = (byte) (0x80 | character & 0x3f);
        }
        characters++;
    }

    @Override
    public void append(CharSequence text) {
        for (int index = 0; index < text.length(); index++) {
            append(text.charAt(index));
        }
    }

    @Override
    public void append(long number) {
        int digits = JsonDecimal.length(number);
        if (digits > JsonOutputLimits.MAX_RECORD_CHARACTERS - characters) {
            throw JsonLimitExceeded.INSTANCE;
        }
        reserve(digits);
        int end = length + digits;
        int cursor = end;
        // Keep the magnitude negative: Long.MIN_VALUE has no positive long counterpart.
        long remaining = number > 0 ? -number : number;
        do {
            bytes[--cursor] = (byte) ('0' - remaining % 10);
            remaining /= 10;
        } while (remaining != 0);
        if (number < 0) bytes[--cursor] = '-';
        length = end;
        characters += digits;
    }

    @Override
    public void append(boolean flag) {
        append(Boolean.toString(flag));
    }

    byte[] bytes() {
        return bytes;
    }

    int length() {
        return length;
    }

    int capacity() {
        return bytes.length;
    }

    private void reserve(int additional) {
        if (additional > MAX_RECORD_BYTES - length) {
            throw JsonLimitExceeded.INSTANCE;
        }
        int needed = length + additional;
        if (needed > bytes.length) {
            bytes = Arrays.copyOf(bytes, Math.min(MAX_RECORD_BYTES, Math.max(needed, bytes.length * 2)));
        }
    }
}
