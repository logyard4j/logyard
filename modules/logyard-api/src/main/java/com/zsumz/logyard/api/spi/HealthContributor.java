package com.zsumz.logyard.api.spi;

import com.zsumz.logyard.api.diagnostics.ComponentHealth;

/** Optional bounded operational introspection implemented by runtime components. */
public interface HealthContributor {
    ComponentHealth health(String componentName);
}
