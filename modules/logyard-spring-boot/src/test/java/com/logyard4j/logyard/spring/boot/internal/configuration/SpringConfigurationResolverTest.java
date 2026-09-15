package com.logyard4j.logyard.spring.boot.internal.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.logyard4j.logyard.api.Logyard;
import com.logyard4j.logyard.runtime.bootstrap.LogyardConfigurationSource;

import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

final class SpringConfigurationResolverTest {
    @AfterAll
    static void cleanUp() {
        Logyard.shutdown();
    }

    @Test
    void rejectsOversizedLocationsBeforeFrameworkHandoff() {
        StandardEnvironment environment = environment(Map.of("logyard.config", " x".repeat(16_385)));
        ClassLoader loader = getClass().getClassLoader();

        assertThrows(IllegalArgumentException.class,
                () -> SpringConfigurationResolver.resolve(loader, environment, null));
        assertThrows(IllegalArgumentException.class,
                () -> SpringConfigurationResolver.resolve(loader, environment(Map.of()), " x".repeat(16_385)));
    }

    @Test
    void equivalentClasspathAliasesSelectTheSameSource() {
        StandardEnvironment environment = environment(Map.of(
                "logyard.config", " \tclasspath:///config/logyard.toml\r\n",
                "logging.config", "classpath:config/logyard.toml"));

        LogyardConfigurationSource source = SpringConfigurationResolver.resolve(
                getClass().getClassLoader(), environment, "classpath:config/logyard.toml");

        assertEquals("classpath:config/logyard.toml", source.description());
    }

    private static StandardEnvironment environment(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        return environment;
    }
}
