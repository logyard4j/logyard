package com.logyard4j.api.event;

import java.text.FieldPosition;
import java.text.Format;
import java.text.ParsePosition;
import java.time.ZoneId;
import java.util.Locale;

/** {@link Format} adapter over Logyard's trusted date/time renderer. */
final class TrustedDateTimeFormat extends Format {
    private static final long serialVersionUID = 1L;

    private final String type;
    private final String style;
    private final Locale locale;
    private final ZoneId zone;

    TrustedDateTimeFormat(String type, String style, Locale locale, ZoneId zone) {
        TrustedDateTimeRenderer.requireSupportedMessageStyle(style);
        this.type = type;
        this.style = style;
        this.locale = locale;
        this.zone = zone;
    }

    @Override
    public StringBuffer format(Object value, StringBuffer target, FieldPosition position) {
        return target.append(TrustedDateTimeRenderer.messageFormat(value, type, style, locale, zone));
    }

    @Override
    public Object parseObject(String source, ParsePosition position) {
        position.setErrorIndex(position.getIndex());
        return null;
    }
}
