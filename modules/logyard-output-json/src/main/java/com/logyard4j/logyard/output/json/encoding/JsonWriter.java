package com.logyard4j.logyard.output.json.encoding;

/** Direct JSON token writer with a hard character bound and bounded-value rendering support. */
final class JsonWriter {
    private final JsonTokenBuffer buffer;
    private final JsonValueWriter values;

    JsonWriter(JsonTokenBuffer buffer) {
        this.buffer = buffer;
        values = new JsonValueWriter(this);
        reset();
    }

    void reset() {
        buffer.reset();
        values.reset();
    }

    boolean traversalTruncated() {
        return values.traversalTruncated();
    }

    boolean claimEntry() {
        return values.claimEntry();
    }

    void markTraversalTruncated() {
        values.markTraversalTruncated();
    }

    void beginObject() {
        buffer.append('{');
    }

    void endObject() {
        buffer.append('}');
    }

    void beginArray() {
        buffer.append('[');
    }

    void endArray() {
        buffer.append(']');
    }

    void comma() {
        buffer.append(',');
    }

    void name(String value) {
        string(value);
        buffer.append(':');
    }

    void field(String name, String value) {
        name(name);
        string(value);
    }

    void field(String name, long value) {
        name(name);
        literal(value);
    }

    void field(String name, boolean value) {
        name(name);
        literal(value);
    }

    void number(long value) {
        literal(value);
    }

    void literal(long value) {
        buffer.append(value);
    }

    void literal(boolean value) {
        buffer.append(value);
    }

    void literal(CharSequence value) {
        buffer.append(value);
    }

    void string(String value) {
        buffer.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> buffer.append("\\\"");
                case '\\' -> buffer.append("\\\\");
                case '\b' -> buffer.append("\\b");
                case '\f' -> buffer.append("\\f");
                case '\n' -> buffer.append("\\n");
                case '\r' -> buffer.append("\\r");
                case '\t' -> buffer.append("\\t");
                default -> {
                    if (character < 0x20 || Character.isSurrogate(character)) {
                        appendUnicode(character);
                    } else {
                        buffer.append(character);
                    }
                }
            }
        }
        buffer.append('"');
    }

    void value(Object value) {
        values.write(value);
    }

    private void appendUnicode(char character) {
        String hex = Integer.toHexString(character);
        buffer.append("\\u");
        buffer.append("0".repeat(4 - hex.length()));
        buffer.append(hex);
    }

}
