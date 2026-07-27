package com.zsumz.logyard.api.event;

/**
 * Fixed limits that make one captured event's memory use finite.
 *
 * <p>The published numeric values are stable API constants. A future release may add a new
 * limit, but it will not silently change an existing inlined value.</p>
 */
public final class CaptureLimits {
    /** Maximum positional arguments retained by one event. */
    public static final int MAX_ARGUMENTS = 64;

    /** Maximum attributes retained by one event, including truncation markers. */
    public static final int MAX_ATTRIBUTES = 128;

    /** Maximum elements retained from a captured collection, map, or array. */
    public static final int MAX_COLLECTION_ELEMENTS = 128;

    /** Maximum recursive depth retained from nested values. */
    public static final int MAX_NESTING_DEPTH = 8;

    /** Maximum UTF-16 characters retained from ordinary captured text. */
    public static final int MAX_TEXT_CHARS = 65_536;

    /** Maximum UTF-16 characters retained from names used in diagnostics and routing. */
    public static final int MAX_NAME_CHARS = 1_024;

    /** Maximum UTF-16 characters retained from an attribute key. */
    public static final int MAX_ATTRIBUTE_KEY_CHARS = 256;

    /** Maximum distinct non-scalar values captured across one event. */
    public static final int MAX_EVENT_NODES = 2_048;

    /** Maximum container entries, arguments, attributes, and frames captured across one event. */
    public static final int MAX_EVENT_ENTRIES = 4_096;

    /** Maximum UTF-16 characters retained across all captured and rendered text in one event. */
    public static final int MAX_EVENT_TEXT_CHARS = 65_536;

    /** Reserved event-wide allowance for logger, event, and thread identity. */
    public static final int MAX_EVENT_IDENTITY_CHARS = 4_096;

    /** Reserved event-wide allowance for the message template. */
    public static final int MAX_EVENT_TEMPLATE_CHARS = 8_192;

    /** Reserved event-wide allowance for the lazily rendered primary message. */
    public static final int MAX_RENDERED_MESSAGE_CHARS = 16_384;

    /** Maximum estimated characters a JDK-style adapter formatter may construct before bounded capture. */
    public static final int MAX_FORMATTER_WORK_CHARS = 65_536;

    /** Reserved event-wide allowance for exception types, messages, and stack-frame fields. */
    public static final int MAX_EVENT_EXCEPTION_TEXT_CHARS = 16_384;

    /** Reserved portion of the exception allowance for throwable type names. */
    public static final int MAX_EVENT_EXCEPTION_TYPE_CHARS = 2_048;

    /** Reserved portion of the exception allowance for throwable messages. */
    public static final int MAX_EVENT_EXCEPTION_MESSAGE_CHARS = 8_192;

    /** Reserved portion of the exception allowance for stack-frame fields. */
    public static final int MAX_EVENT_EXCEPTION_FRAME_CHARS = 6_144;

    /** Event-wide allowance for argument, attribute, and processor-enrichment text. */
    public static final int MAX_EVENT_PAYLOAD_TEXT_CHARS = 20_480;

    /** Maximum canonical characters retained for one arbitrary-precision number. */
    public static final int MAX_CAPTURED_NUMBER_CHARS = 2_048;

    /** Maximum throwable nodes captured across one event. */
    public static final int MAX_EVENT_EXCEPTION_NODES = 64;

    /** Maximum stack frames captured across one event. */
    public static final int MAX_EVENT_STACK_FRAMES = 1_024;

    private CaptureLimits() {
    }

    /**
     * Bounds arbitrary captured text without splitting a surrogate pair.
     *
     * @param value text to bound, or {@code null}
     * @return bounded text, preserving {@code null}
     */
    public static String text(String value) {
        return SurrogateSafeText.truncate(value, MAX_TEXT_CHARS);
    }

    /**
     * Bounds a logger, output, processor, or other diagnostic name.
     *
     * @param value name to bound, or {@code null}
     * @return bounded name, preserving {@code null}
     */
    public static String name(String value) {
        return SurrogateSafeText.truncate(value, MAX_NAME_CHARS);
    }

    /**
     * Produces the bounded storage form of an attribute key while preserving its leaf segment.
     *
     * @param value non-null attribute key
     * @return bounded key suitable for storage and leaf-based security matching
     */
    public static String attributeKey(String value) {
        return BoundedAttributeKey.normalize(value);
    }

    static String disambiguateAttributeKey(String key, int collisionIndex) {
        return BoundedAttributeKey.disambiguate(key, collisionIndex);
    }

    static String truncate(String value, int maximum) {
        return SurrogateSafeText.truncate(value, maximum);
    }
}
