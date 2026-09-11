package com.logyard4j.api.spi.diagnostics;

import com.logyard4j.api.diagnostics.ComponentHealth;

/**
 * Optional bounded operational introspection implemented by runtime components.
 *
 * <p>Health capture may run concurrently with delivery and lifecycle operations. Implementations
 * must be thread-safe, return a detached bounded snapshot, and avoid unbounded blocking work.</p>
 */
public interface HealthContributor {
    /**
     * Returns a point-in-time health snapshot.
     *
     * @param componentName runtime-assigned component name
     * @return bounded component health
     */
    ComponentHealth health(String componentName);
}
