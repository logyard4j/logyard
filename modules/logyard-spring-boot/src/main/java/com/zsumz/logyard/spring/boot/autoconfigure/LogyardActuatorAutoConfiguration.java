package com.zsumz.logyard.spring.boot.autoconfigure;

import com.zsumz.logyard.spring.boot.internal.actuator.LogyardHealthContributorRegistrar;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** Adds a live Actuator health contributor when either maintained health API is present. */
@AutoConfiguration(after = LogyardAutoConfiguration.class)
@ConditionalOnProperty(prefix = "logyard", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LogyardActuatorAutoConfiguration {
    /** Registers a contributor with the health interface supplied by the active Boot line. */
    @Bean
    @ConditionalOnMissingBean(name = LogyardHealthContributorRegistrar.BEAN_NAME)
    public static LogyardHealthContributorRegistrar logyardHealthContributorRegistrar() {
        return new LogyardHealthContributorRegistrar();
    }
}
