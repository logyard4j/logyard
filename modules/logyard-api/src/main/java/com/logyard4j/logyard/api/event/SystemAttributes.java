package com.logyard4j.logyard.api.event;

/**
 * Names of the diagnostic attributes Logyard writes in its reserved {@code logyard.*}
 * namespace.
 *
 * <p>These names are stable API for encoder, formatter, and processor implementations.
 * Application attributes must not use the reserved namespace; see
 * {@link AttributeSet#isReservedKey(String)}.</p>
 */
public final class SystemAttributes {
    /** Set to {@code true} when ingress capture lost any argument, attribute, or text. */
    public static final String CAPTURE_TRUNCATED = "logyard.capture.truncated";
    /** Set to {@code true} when the attribute set could not hold every source attribute. */
    public static final String ATTRIBUTES_TRUNCATED = "logyard.attributes.truncated";
    /** Number of positional arguments omitted beyond {@link CaptureLimits#MAX_ARGUMENTS}. */
    public static final String ARGUMENTS_OMITTED = "logyard.arguments.omitted";
    /**
     * Marker key recording capture-bound cuts inside a value tree; disambiguated
     * numeric suffixes ({@code logyard.truncated.2}) keep repeated markers distinct.
     */
    public static final String VALUE_TRUNCATED = "logyard.truncated";
    /** Set by built-in outputs when their own rendering limits shortened a record. */
    public static final String OUTPUT_TRUNCATED = "logyard.output.truncated";

    /** Set when redaction shortened an attribute tree while preserving its bounds. */
    public static final String REDACTION_TRUNCATED = "logyard.redaction.truncated";

    private SystemAttributes() {
    }
}
