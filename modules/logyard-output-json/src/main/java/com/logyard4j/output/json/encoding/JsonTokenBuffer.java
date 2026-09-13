package com.logyard4j.output.json.encoding;

/** Bounded storage for JSON tokens whose surrogate code units have already been escaped. */
interface JsonTokenBuffer {
    void reset();

    void append(char character);

    void append(CharSequence text);

    void append(long number);

    void append(boolean flag);
}
