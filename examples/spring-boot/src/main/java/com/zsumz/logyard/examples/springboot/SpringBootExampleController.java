package com.zsumz.logyard.examples.springboot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class SpringBootExampleController {
    private static final Logger LOGGER = LoggerFactory.getLogger(SpringBootExampleController.class);
    private static final String REQUEST_ID = "request.id";

    private final SpringBootExampleShutdown shutdown;

    public SpringBootExampleController(SpringBootExampleShutdown shutdown) {
        this.shutdown = shutdown;
    }

    @GetMapping(value = "/success", produces = "text/plain")
    public String success(@RequestHeader(name = "X-Request-Id", required = false) String requestId) {
        LOGGER.atInfo().addKeyValue(REQUEST_ID, requestId(requestId)).log("Spring Boot request succeeded");
        return "success";
    }

    @GetMapping(value = "/debug", produces = "text/plain")
    public String debug(@RequestHeader(name = "X-Request-Id", required = false) String requestId) {
        LOGGER.atDebug().addKeyValue(REQUEST_ID, requestId(requestId)).log("Spring Boot dynamic debug");
        return "debug";
    }

    @GetMapping(value = "/failure", produces = "text/plain")
    public ResponseEntity<String> failure(@RequestHeader(name = "X-Request-Id", required = false) String requestId) {
        IllegalStateException failure = new IllegalStateException("expected Spring Boot example failure");
        LOGGER.atError()
                .addKeyValue(REQUEST_ID, requestId(requestId))
                .setCause(failure)
                .log("Spring Boot request failed");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("failure");
    }

    @PostMapping(value = "/shutdown", produces = "text/plain")
    public ResponseEntity<String> shutdown() {
        shutdown.afterResponse();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body("stopping");
    }

    private static String requestId(String value) {
        return value == null || value.isBlank() ? "missing-request-id" : value;
    }
}
