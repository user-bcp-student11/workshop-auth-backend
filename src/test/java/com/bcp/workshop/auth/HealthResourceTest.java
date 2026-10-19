package com.bcp.workshop.auth;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(AuthResourceTest.IsolatedDatabase.class)
class HealthResourceTest {
    @Test
    void healthIsAvailableWithoutAuthentication() {
        var response = given().accept("application/json")
                .when().get("/q/health");
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                "The documented health endpoint must be available without credentials");
        System.out.println("QA health observation: HTTP " + response.statusCode()
                + ", Content-Type " + response.contentType() + ", body " + response.asString());
    }

    @Test
    void healthAllowsOmittingTheRecommendedAcceptHeader() {
        var response = given().when().get("/q/health");
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                "The recommended Accept header is not documented as mandatory");
    }
}