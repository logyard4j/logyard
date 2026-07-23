package com.zsumz.logyard.api.spi.diagnostics;

import com.zsumz.logyard.api.diagnostics.ComponentHealth;

/** Optional bounded operational introspection implemented by runtime components. */
public interface HealthContributor {
    /**
     * Returns a point-in-time health snapshot.
     *
     * @param componentName runtime-assigned component name
     * @return bounded component health
     */
    ComponentHealth health(String componentName);
}
