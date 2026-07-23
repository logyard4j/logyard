package com.zsumz.logyard.examples.micronaut;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Controller
public final class MicronautExampleController {
    private static final Logger LOGGER = LoggerFactory.getLogger(MicronautExampleController.class);
    private static final String REQUEST_ID = "request.id";

    private final DeferredShutdown shutdown;

    public MicronautExampleController(DeferredShutdown shutdown) {
        this.shutdown = shutdown;
    }

    @Get("/success")
    @Produces(MediaType.TEXT_PLAIN)
    public HttpResponse<String> success(HttpRequest<?> request) {
        LOGGER.atInfo().addKeyValue(REQUEST_ID, requestId(request)).log("Micronaut request succeeded");
        return HttpResponse.ok("success");
    }

    @Get("/failure")
    @Produces(MediaType.TEXT_PLAIN)
    public HttpResponse<String> failure(HttpRequest<?> request) {
        IllegalStateException failure = new IllegalStateException("expected Micronaut example failure");
        LOGGER.atError().addKeyValue(REQUEST_ID, requestId(request)).setCause(failure).log("Micronaut request failed");
        return HttpResponse.serverError("failure");
    }

    @Post("/shutdown")
    @Produces(MediaType.TEXT_PLAIN)
    public HttpResponse<String> shutdown() {
        shutdown.afterResponse();
        return HttpResponse.<String>status(HttpStatus.ACCEPTED).body("stopping");
    }

    private static String requestId(HttpRequest<?> request) {
        String requestId = request.getHeaders().get("X-Request-Id");
        return requestId == null || requestId.isBlank() ? "missing-request-id" : requestId;
    }
}
