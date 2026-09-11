package com.logyard4j.examples.springboot;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Demonstrates explicit incoming context extraction and executor propagation. */
@RestController
@SuppressWarnings("try")
public final class TraceExampleController {
    private static final Logger LOGGER = LoggerFactory.getLogger(TraceExampleController.class);
    private static final TextMapGetter<HttpHeaders> HEADERS = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(HttpHeaders carrier) {
            return carrier.headerSet().stream().map(java.util.Map.Entry::getKey).toList();
        }

        @Override
        public String get(HttpHeaders carrier, String key) {
            return carrier == null ? null : carrier.getFirst(key);
        }
    };

    @GetMapping("/trace")
    public String trace(@RequestHeader HttpHeaders headers) throws Exception {
        Context incoming = W3CTraceContextPropagator.getInstance().extract(Context.root(), headers, HEADERS);
        try (Scope ignored = incoming.makeCurrent(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            LOGGER.info("Spring Boot trace request");
            executor.submit(incoming.wrap(() -> LOGGER.info("Spring Boot trace executor"))).get(5, TimeUnit.SECONDS);
        }
        LOGGER.info("Spring Boot trace scope closed");
        return "trace";
    }
}
