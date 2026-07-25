/**
 * Immutable, bounded event values crossing Logyard's capture and output boundaries.
 *
 * <p>Adapter date/time formatting accepts exact {@link java.util.Date} values and epoch-millisecond
 * {@link java.lang.Long} values. It uses UTC and the proleptic Gregorian calendar deterministically;
 * {@link java.util.Calendar}, {@link java.time.temporal.TemporalAccessor}, and {@code Date} subclasses
 * are deliberately outside the safe temporal conversion set.</p>
 */
package com.zsumz.logyard.api.event;
