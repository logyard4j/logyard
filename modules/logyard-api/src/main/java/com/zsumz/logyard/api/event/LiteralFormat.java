package com.zsumz.logyard.api.event;

import java.text.FieldPosition;
import java.text.Format;
import java.text.ParsePosition;

/** A formatter that writes an already-bounded literal branch result. */
final class LiteralFormat extends Format {
    private static final long serialVersionUID = 1L;

    private final String value;

    LiteralFormat(String value) {
        this.value = value;
    }

    @Override
    public StringBuffer format(Object ignored, StringBuffer target, FieldPosition position) {
        return target.append(value);
    }

    @Override
    public Object parseObject(String source, ParsePosition position) {
        position.setErrorIndex(position.getIndex());
        return null;
    }
}
