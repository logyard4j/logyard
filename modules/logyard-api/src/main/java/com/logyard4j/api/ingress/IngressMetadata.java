package com.logyard4j.api.ingress;

import com.logyard4j.api.event.CaptureLimits;

/**
 * Optional source metadata supplied by a compatibility adapter at Logyard's ingress boundary.
 *
 * <p>The runtime always records its own observed timestamp. This value preserves a façade
 * event's source timestamp and logical thread identity without coupling Logyard to that façade's
 * event type.</p>
 */
public final class IngressMetadata {
    private static final IngressMetadata CURRENT =
            new IngressMetadata(false, 0L, false, 0L, null);

    private final boolean sourceTimestampPresent;
    private final long sourceTimestampMillis;
    private final boolean sourceThreadIdPresent;
    private final long sourceThreadId;
    private final String sourceThreadName;

    private IngressMetadata(
            boolean sourceTimestampPresent,
            long sourceTimestampMillis,
            boolean sourceThreadIdPresent,
            long sourceThreadId,
            String sourceThreadName) {
        this.sourceTimestampPresent = sourceTimestampPresent;
        this.sourceTimestampMillis = sourceTimestampMillis;
        this.sourceThreadIdPresent = sourceThreadIdPresent;
        this.sourceThreadId = sourceThreadId;
        this.sourceThreadName = normalize(sourceThreadName);
    }

    /**
     * Uses the capture timestamp and current thread identity.
     *
     * @return shared metadata indicating that no source values override capture values
     */
    public static IngressMetadata current() {
        return CURRENT;
    }

    /**
     * Preserves an external timestamp and thread name; the source thread id is unknown.
     *
     * @param timestampMillis source timestamp in Unix epoch milliseconds
     * @param threadName source thread name
     * @return source metadata
     */
    public static IngressMetadata source(long timestampMillis, String threadName) {
        return new IngressMetadata(true, timestampMillis, false, 0L, threadName);
    }

    /**
     * Preserves an external timestamp and complete source thread identity.
     *
     * @param timestampMillis source timestamp in Unix epoch milliseconds
     * @param threadId source thread identifier
     * @param threadName source thread name
     * @return source metadata
     */
    public static IngressMetadata source(long timestampMillis, long threadId, String threadName) {
        return new IngressMetadata(true, timestampMillis, true, threadId, threadName);
    }

    /**
     * Returns whether a source timestamp is present.
     *
     * @return {@code true} when a source timestamp is present
     */
    public boolean hasSourceTimestamp() {
        return sourceTimestampPresent;
    }

    /**
     * Returns the source timestamp.
     *
     * @return source timestamp in Unix epoch milliseconds
     * @throws IllegalStateException if no source timestamp is present
     */
    public long sourceTimestampMillis() {
        if (!sourceTimestampPresent) {
            throw new IllegalStateException("source timestamp is not present");
        }
        return sourceTimestampMillis;
    }

    /**
     * Returns whether a source thread identifier is present.
     *
     * @return {@code true} when a source thread identifier is present
     */
    public boolean hasSourceThreadId() {
        return sourceThreadIdPresent;
    }

    /**
     * Returns the source thread identifier.
     *
     * @return source thread identifier
     * @throws IllegalStateException if no source thread identifier is present
     */
    public long sourceThreadId() {
        if (!sourceThreadIdPresent) {
            throw new IllegalStateException("source thread id is not present");
        }
        return sourceThreadId;
    }

    /**
     * Returns the normalized source thread name, or {@code null}.
     *
     * @return source thread name, or {@code null}
     */
    public String sourceThreadName() {
        return sourceThreadName;
    }

    private static String normalize(String threadName) {
        if (threadName == null) {
            return null;
        }
        String normalized = threadName.trim();
        return normalized.isEmpty() ? null : CaptureLimits.name(normalized);
    }
}
