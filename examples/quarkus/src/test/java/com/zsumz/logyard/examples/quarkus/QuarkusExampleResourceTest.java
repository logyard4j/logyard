package com.zsumz.logyard.examples.quarkus;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

@QuarkusTest
final class QuarkusExampleResourceTest {
    @Test
    void servesSuccessAndFailureInQuarkusTestMode() {
        given()
                .header("X-Request-Id", "test-success")
                .when().get("/success")
                .then().statusCode(200).body(is("success"));
        given()
                .header("X-Request-Id", "test-failure")
                .when().get("/failure")
                .then().statusCode(500).body(is("failure"));
    }
}
