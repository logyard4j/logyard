/**
 * Bounded sink, context, health, and event-enrichment extension contracts.
 *
 * <p>Providers are discovered through {@link java.util.ServiceLoader}, validated
 * before construction, and owned by one immutable runtime plan. Implementations
 * should avoid unbounded queues and must release owned resources when closed.</p>
 */
package com.zsumz.logyard.api.spi;
