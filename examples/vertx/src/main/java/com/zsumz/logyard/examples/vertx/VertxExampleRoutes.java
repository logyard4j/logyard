package com.zsumz.logyard.examples.vertx;

import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class VertxExampleRoutes {
    private static final Logger LOGGER = LoggerFactory.getLogger(VertxExampleRoutes.class);
    private static final String REQUEST_ID = "request.id";

    private final Vertx vertx;

    VertxExampleRoutes(Vertx vertx) {
        this.vertx = vertx;
    }

    void install(Router router) {
        router.get("/success").handler(this::success);
        router.get("/failure").handler(this::failure);
        router.post("/shutdown").handler(this::shutdown);
    }

    private void success(RoutingContext context) {
        String requestId = requestId(context);
        context.put(REQUEST_ID, requestId);
        String storedRequestId = context.get(REQUEST_ID);
        LOGGER.atInfo().addKeyValue(REQUEST_ID, storedRequestId).log("Vert.x request succeeded");
        context.response().end("success");
    }

    private void failure(RoutingContext context) {
        String requestId = requestId(context);
        context.put(REQUEST_ID, requestId);
        String storedRequestId = context.get(REQUEST_ID);
        IllegalStateException failure = new IllegalStateException("expected Vert.x example failure");
        LOGGER.atError().addKeyValue(REQUEST_ID, storedRequestId).setCause(failure).log("Vert.x request failed");
        context.response().setStatusCode(500).end("failure");
    }

    private void shutdown(RoutingContext context) {
        context.response().setStatusCode(202).end("stopping").onComplete(ignored -> {
            LOGGER.atInfo().addKeyValue("phase", "shutdown").log("Vert.x shutdown flush");
            vertx.close().onComplete(closed -> System.exit(closed.succeeded() ? 0 : 1));
        });
    }

    private static String requestId(RoutingContext context) {
        String requestId = context.request().getHeader("X-Request-Id");
        return requestId == null || requestId.isBlank() ? "missing-request-id" : requestId;
    }
}
