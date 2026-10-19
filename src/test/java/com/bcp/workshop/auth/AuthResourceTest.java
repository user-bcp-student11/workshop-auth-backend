package com.bcp.workshop.auth;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.HashMap;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.microprofile.config.ConfigProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@TestProfile(AuthResourceTest.IsolatedDatabase.class)
class AuthResourceTest {
    private static final String STUDENT_PASSWORD = "Workshop123!";
    private static final Path DATABASE_PATH = Path.of("target", "qa-http-" + ProcessHandle.current().pid() + ".db");

    public static class IsolatedDatabase implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("auth.database.url", "jdbc:sqlite:" + DATABASE_PATH,
                    "auth.seed.student-password", STUDENT_PASSWORD,
                    "auth.seed.trainer-password", "Trainer123!");
        }
    }

    @AfterAll
    static void removeDatabase() throws Exception {
        Files.deleteIfExists(DATABASE_PATH);
    }

    @BeforeAll
    static void insertCharacterizationUsers() throws Exception {
        new LegacyDatabase().initialize();
        try (var connection = DriverManager.getConnection(ConfigProvider.getConfig().getValue("auth.database.url", String.class));
             var statement = connection.prepareStatement("INSERT OR IGNORE INTO legacy_users(username,password_hash,display_name,role) VALUES (?,?,?,?)")) {
            statement.setString(1, "html-user");
            statement.setString(2, new PasswordHasher().hash("TestPassword!"));
            statement.setString(3, "<b>QA & Test</b>");
            statement.setString(4, "STUDENT");
            statement.executeUpdate();
            statement.setString(1, "qa'quoted");
            statement.setString(2, new PasswordHasher().hash("TestPassword!"));
            statement.setString(3, "Quoted User");
            statement.executeUpdate();
        }
    }

    @Test
    void logsInWithWorkshopUser() {
        given()
                .contentType("application/json")
                .body("{\"username\":\"student\",\"password\":\"Workshop123!\",\"client\":\"angular-workshop\"}")
                .when().post("/auth/login")
                .then()
                .statusCode(200)
                .body("user.username", is("student"))
                .body("token", notNullValue());
    }

    @Test
    void preservesCompleteSuccessfulResponse() {
        assertSuccessfulLogin("student", STUDENT_PASSWORD, "Workshop Student", "STUDENT");
        assertSuccessfulLogin("trainer", "Trainer123!", "Workshop Trainer", "TRAINER");
        assertSuccessfulLogin("html-user", "TestPassword!", "&lt;b&gt;QA &amp; Test&lt;/b&gt;", "STUDENT");
    }

    @Test
    void rejectsInvalidCredentialsWithoutTrimming() {
        assertRejected("missing-user", STUDENT_PASSWORD);
        assertRejected("student", "incorrect");
        assertRejected(" student ", STUDENT_PASSWORD);
        assertRejected("student", " " + STUDENT_PASSWORD + " ");
    }

    @Test
    void preservesRequiredFieldsAndValidationOrder() {
        assertValidation(Map.of(), "Username is required");
        for (String blank : new String[]{"", "   "}) {
            assertValidation(Map.of("username", blank, "password", ""), "Username is required");
            assertValidation(Map.of("username", "student", "password", blank), "Password is required");
        }
        Map<String, Object> request = new HashMap<>();
        request.put("username", null);
        request.put("password", null);
        assertValidation(request, "Username is required");
        request.put("username", "student");
        assertValidation(request, "Password is required");
        assertValidation(Map.of("username", "student"), "Password is required");
    }

    @Test
    void acceptsOptionalClientAndUnknownProperties() {
        given().contentType("application/json")
                .body(Map.of("username", "student", "password", STUDENT_PASSWORD, "unknown", "ignored"))
                .when().post("/auth/login").then().statusCode(200);
    }

    @Test
    void preservesNullRequestResponse() {
        try (var response = new AuthResource().login(null)) {
            assertEquals(400, response.getStatus());
            assertEquals(Map.of("message", "Request is null"), response.getEntity());
        }
    }

    @Test
    void preservesUnexpectedErrorResponse() {
        for (String message : new String[]{"Controlled QA failure", null}) {
            var resource = new AuthResource();
            resource.authentication = new AuthenticationService(null) {
                @Override
                public AuthenticatedUser authenticate(String username, String password) {
                    throw new IllegalStateException(message);
                }
            };
            var request = new LoginRequest();
            request.username = "student";
            request.password = STUDENT_PASSWORD;
            try (var response = resource.login(request)) {
                assertEquals(500, response.getStatus());
                assertEquals(Map.of("message", message == null ? "Unknown error" : message,
                        "exception", IllegalStateException.class.getName()), response.getEntity());
            }
        }
    }

    @Test
    void rejectsSqlInUsername() {
        assertRejected("student' --", "incorrect");
    }

    @Test
    void rejectsSqlInPassword() {
        assertRejected("student", "' OR '1'='1");
    }

    @Test
    void authenticatesLiteralQuotedUsername() {
        assertSuccessfulLogin("qa'quoted", "TestPassword!", "Quoted User", "STUDENT");
    }

    private void assertSuccessfulLogin(String username, String password, String displayName, String role) {
        var response = given().contentType("application/json")
                .body(Map.of("username", username, "password", password))
                .when().post("/auth/login").then().statusCode(200).contentType("application/json")
            .extract().jsonPath();
        Map<String, Object> body = response.getMap("");
        assertEquals(java.util.Set.of("token", "expiresIn", "user", "debug"), body.keySet());
        assertNotNull(body.get("token"));
        assertFalse(((String) body.get("token")).isBlank());
        assertEquals(3600, body.get("expiresIn"));
        assertEquals("authenticated-with-legacy-flow", body.get("debug"));
        assertEquals(Map.of("username", username, "displayName", displayName, "role", role), body.get("user"));
    }

    private void assertRejected(String username, String password) {
        given().contentType("application/json").body(Map.of("username", username, "password", password))
                .when().post("/auth/login").then().statusCode(401)
                .body("message", is("Invalid user " + username + " or password"));
    }

    private void assertValidation(Map<String, ?> request, String message) {
        given().contentType("application/json").body(request)
                .when().post("/auth/login").then().statusCode(400).body("message", is(message));
    }
}
