package com.zsumz.logyard.runtime.installation.process;

import com.zsumz.logyard.runtime.installation.ConfigurationInstallationRequest;
import com.zsumz.logyard.runtime.installation.RuntimeInstallation;
import java.util.Map;

/** Constructs a complete runtime installation outside the process lifecycle lock. */
@FunctionalInterface
interface RuntimeInstallationFactory {
    RuntimeInstallation open(ConfigurationInstallationRequest request, Map<String, String> environment);
}
