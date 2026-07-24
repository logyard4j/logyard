package com.zsumz.logyard.spring.boot.autoconfigure;

import com.zsumz.logyard.spring.boot.internal.actuator.LogyardHealthContributorRegistrar;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Import;

/** Adds a live Actuator health contributor when either maintained health API is present. */
@AutoConfiguration(after = LogyardAutoConfiguration.class)
@ConditionalOnProperty(prefix = "logyard", name = "enabled", havingValue = "true", matchIfMissing = true)
@Import(LogyardHealthContributorRegistrar.class)
public class LogyardActuatorAutoConfiguration {
}
