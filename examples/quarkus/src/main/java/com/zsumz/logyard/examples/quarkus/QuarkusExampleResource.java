package com.zsumz.logyard.examples.quarkus;

import io.quarkus.runtime.Quarkus;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Path("/")
@Produces(MediaType.TEXT_PLAIN)
public final class QuarkusExampleResource {
    private static final Logger LOGGER = Logger.getLogger(QuarkusExampleResource.class);
    private static final AtomicBoolean STOPPING = new AtomicBoolean();

    @GET
    @Path("success")
    public String success(@HeaderParam("X-Request-Id") String requestId) {
        withRequest(requestId, () -> LOGGER.info("Quarkus request succeeded"));
        return "success";
    }

    @GET
    @Path("failure")
    public Response failure(@HeaderParam("X-Request-Id") String requestId) {
        IllegalStateException failure = new IllegalStateException("expected Quarkus example failure");
        withRequest(requestId, () -> LOGGER.error("Quarkus request failed", failure));
        return Response.serverError().entity("failure").build();
    }

    @POST
    @Path("shutdown")
    public Response shutdown() {
        if (STOPPING.compareAndSet(false, true)) {
            LOGGER.info("Quarkus shutdown flush");
            CompletableFuture.runAsync(
                    Quarkus::asyncExit,
                    CompletableFuture.delayedExecutor(100L, TimeUnit.MILLISECONDS));
        }
        return Response.accepted("stopping").build();
    }

    private static void withRequest(String requestId, Runnable action) {
        MDC.put("request.id", requestId);
        MDC.put("authorization", "Bearer quarkus-example-secret");
        MDC.put("session.token", "quarkus-example-token");
        try {
            action.run();
        } finally {
            MDC.remove("request.id");
            MDC.remove("authorization");
            MDC.remove("session.token");
        }
    }
}
