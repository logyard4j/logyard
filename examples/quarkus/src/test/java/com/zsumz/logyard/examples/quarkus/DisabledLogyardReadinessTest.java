package com.zsumz.logyard.examples.quarkus;

import com.zsumz.logyard.api.Logyard;
import com.zsumz.logyard.quarkus.runtime.logging.QuarkusLogHandler;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.logging.Logger;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
@TestProfile(DisabledLogyardReadinessTest.DisabledLogyardProfile.class)
final class DisabledLogyardReadinessTest {
    @Test
    void disabledExtensionIsReadyWithoutAHandlerOrRuntime() {
        given()
                .when().get("/q/health/ready")
                .then()
                .statusCode(200)
                .body("status", org.hamcrest.Matchers.equalTo("UP"))
                .body("checks.name", hasItem("logyard"))
                .body("checks.status", hasItem("UP"))
                .body("checks.data.status", hasItem("DISABLED"));

        assertNull(Logyard.runtimeOrNull());
        assertFalse(Arrays.stream(Logger.getLogger("").getHandlers()).anyMatch(QuarkusLogHandler.class::isInstance));
    }

    public static final class DisabledLogyardProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.logyard.enabled", "false");
        }
    }
}
