package com.logyard4j.logyard.examples.vertx;

import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import java.util.ServiceLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.SLF4JServiceProvider;

public final class VertxExampleApplication {
    private static final Logger LOGGER = LoggerFactory.getLogger(VertxExampleApplication.class);

    private VertxExampleApplication() {
    }

    public static void main(String[] arguments) {
        requireSingleProvider();
        Vertx vertx = Vertx.vertx();
        Router router = Router.router(vertx);
        new VertxExampleRoutes(vertx).install(router);
        vertx.createHttpServer()
                .requestHandler(router)
                .listen(0)
                .onSuccess(server -> {
                    LOGGER.atInfo().addKeyValue("phase", "startup").log("Vert.x application started");
                    VerificationPort.publish(server.actualPort());
                })
                .onFailure(failure -> {
                    LOGGER.atError().setCause(failure).log("Vert.x application failed to start");
                    vertx.close();
                });
    }

    private static void requireSingleProvider() {
        long providers = ServiceLoader.load(SLF4JServiceProvider.class).stream().count();
        if (providers != 1) {
            throw new IllegalStateException("expected exactly one SLF4J provider, found " + providers);
        }
    }
}
