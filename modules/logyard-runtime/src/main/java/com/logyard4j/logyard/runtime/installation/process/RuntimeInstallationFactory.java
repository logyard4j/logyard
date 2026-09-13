package com.logyard4j.logyard.runtime.installation.process;

import com.logyard4j.logyard.runtime.installation.ConfigurationInstallationRequest;
import com.logyard4j.logyard.runtime.installation.RuntimeInstallation;
import java.util.Map;

/** Constructs a complete runtime installation outside the process lifecycle lock. */
@FunctionalInterface
interface RuntimeInstallationFactory {
    RuntimeInstallation open(ConfigurationInstallationRequest request, Map<String, String> environment);
}
