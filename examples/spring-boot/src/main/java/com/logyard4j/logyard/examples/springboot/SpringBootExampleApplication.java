package com.logyard4j.logyard.examples.springboot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;

@SpringBootApplication
public class SpringBootExampleApplication {
    private static final Logger LOGGER = LoggerFactory.getLogger(SpringBootExampleApplication.class);

    private final Environment environment;

    public SpringBootExampleApplication(Environment environment) {
        this.environment = environment;
    }

    public static void main(String[] arguments) {
        SpringApplication.run(SpringBootExampleApplication.class, arguments);
    }

    @EventListener(ApplicationReadyEvent.class)
    void ready() {
        String port = environment.getRequiredProperty("local.server.port");
        VerificationPort.publish(port);
        LOGGER.atInfo().addKeyValue("phase", "startup").log("Spring Boot application started");
    }

}
