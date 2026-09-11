package com.logyard4j.spring.boot.logging;

import com.logyard4j.spring.boot.internal.configuration.EarlyEnablement;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.logging.LoggingSystemFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/** Selects Logyard before Spring Boot emits its earliest framework events. */
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class LogyardLoggingSystemFactory implements LoggingSystemFactory {
    @Override
    public LoggingSystem getLoggingSystem(ClassLoader classLoader) {
        return EarlyEnablement.isEnabled() ? new LogyardLoggingSystem(classLoader) : null;
    }
}
