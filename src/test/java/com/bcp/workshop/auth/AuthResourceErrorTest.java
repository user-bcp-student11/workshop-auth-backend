package com.bcp.workshop.auth;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(AuthResourceErrorTest.ErrorProfile.class)
class AuthResourceErrorTest {
    public static class ErrorProfile extends AuthResourceTest.IsolatedDatabase implements QuarkusTestProfile {
        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(FailingAuthenticationService.class);
        }
    }

    @Alternative
    @ApplicationScoped
    public static class FailingAuthenticationService extends AuthenticationService {
        public FailingAuthenticationService() {
            super(null);
        }

        @Override
        public AuthenticatedUser authenticate(String username, String password) {
            throw new IllegalStateException("without-message".equals(username) ? null : "Controlled QA failure");
        }
    }

    @Test
    void unexpectedExceptionReturnsTheDocumented500OverHttp() {
        assertServerError("controlled-failure", "Controlled QA failure");
    }

    @Test
    void nullExceptionMessageReturnsUnknownErrorOverHttp() {
        assertServerError("without-message", "Unknown error");
    }

    private void assertServerError(String username, String expectedMessage) {
        Map<String, Object> body = given().contentType("application/json").accept("application/json")
                .body(Map.of("username", username, "password", "QA-only-fixture"))
                .when().post("/auth/login").then().statusCode(500).contentType("application/json")
                .extract().jsonPath().getMap("");
        assertEquals(Map.of("message", expectedMessage, "exception", IllegalStateException.class.getName()), body);
    }
}