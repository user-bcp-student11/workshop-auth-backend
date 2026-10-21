package com.bcp.workshop.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.ConfigProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@TestProfile(AuthResourceTest.IsolatedDatabase.class)
class AuthResourceTest {
    private static final String STUDENT_PASSWORD = "Workshop123!";
    private static final String LONG_PASSWORD = "LongPassword!".repeat(320);
    private static final String TEST_TOKEN_SECRET = "qa-only-token-secret-with-32-bytes!";
    private static final String TEST_CLIENT = "qa-test-client";
    private static final Path DATABASE_PATH = Path.of("target", "qa-http-" + ProcessHandle.current().pid() + ".db");

    public static class IsolatedDatabase implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("auth.database.url", "jdbc:sqlite:" + DATABASE_PATH,
                    "auth.seed.student-password", STUDENT_PASSWORD,
                    "auth.seed.trainer-password", "Trainer123!",
                    "auth.token.secret", TEST_TOKEN_SECRET);
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
            statement.setString(1, "long-user");
            statement.setString(2, new PasswordHasher().hash(LONG_PASSWORD));
            statement.setString(3, "Long Password User");
            statement.executeUpdate();
            statement.setString(1, "corrupt-hash-user");
            statement.setString(2, "invalid-hash");
            statement.setString(3, "Corrupt Hash User");
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
    void doesNotLogJwtOrSigningSecretDuringLogin() {
        Logger logger = Logger.getLogger("com.bcp.workshop.auth");
        var capture = new CapturingLogHandler();
        logger.addHandler(capture);
        String token;
        try {
            token = given().contentType("application/json")
                    .body(Map.of("username", "student", "password", STUDENT_PASSWORD, "client", TEST_CLIENT))
                    .when().post("/auth/login").then().statusCode(200)
                    .extract().path("token");
        } finally {
            logger.removeHandler(capture);
        }
        assertFalse(capture.messages().contains(token));
        assertFalse(capture.messages().contains(TEST_TOKEN_SECRET));
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
    void ignoresUnknownPropertiesWhenClientIsValid() {
        given().contentType("application/json")
                .body(Map.of("username", "student", "password", STUDENT_PASSWORD,
                        "client", TEST_CLIENT, "unknown", "ignored"))
                .when().post("/auth/login").then().statusCode(200);
    }

    @Test
    void rejectsAbsentNullEmptyAndBlankClient() {
        Map<String, Object> request = new HashMap<>();
        request.put("username", "student");
        request.put("password", STUDENT_PASSWORD);
        assertInvalidClient(request);
        request.put("client", null);
        assertInvalidClient(request);
        request.put("client", "");
        assertInvalidClient(request);
        request.put("client", "   ");
        assertInvalidClient(request);
    }

    @Test
    void preservesUsernameCaseAndLongPasswords() {
        assertRejected("Student", STUDENT_PASSWORD);
        assertSuccessfulLogin("long-user", LONG_PASSWORD, "Long Password User", "STUDENT");
        assertRejected("long-user", LONG_PASSWORD.substring(0, LONG_PASSWORD.length() - 1));
    }

    @Test
    void rejectsMalformedStoredHashOverHttp() {
        assertRejected("corrupt-hash-user", "TestPassword!");
    }

    @Test
    void queryFailureReturnsTheDocumented401OverHttp() throws Exception {
        Logger logger = Logger.getLogger("com.bcp.workshop.auth");
        var capture = new CapturingLogHandler();
        logger.addHandler(capture);
        try {
            try (var connection = DriverManager.getConnection(ConfigProvider.getConfig().getValue("auth.database.url", String.class));
                 var statement = connection.createStatement()) {
                statement.executeUpdate("ALTER TABLE legacy_users RENAME TO qa_unavailable_users");
                try {
                    assertRejected("student", STUDENT_PASSWORD);
                } finally {
                    statement.executeUpdate("ALTER TABLE qa_unavailable_users RENAME TO legacy_users");
                }
            }
            assertSuccessfulLogin("student", STUDENT_PASSWORD, "Workshop Student", "STUDENT");
        } finally {
            logger.removeHandler(capture);
        }
        assertTrue(capture.messages().contains("Unable to query authentication storage"));
        assertFalse(capture.messages().contains(TEST_TOKEN_SECRET));
        assertFalse(capture.messages().contains(STUDENT_PASSWORD));
    }

    @Test
    void preservesNullRequestResponse() {
        try (var response = new AuthResource().login(null)) {
            assertEquals(400, response.getStatus());
            assertEquals(Map.of("message", "Request is null"), response.getEntity());
        }
    }

    @Test
    void jsonNullRequestReturnsTheDocumented400OverHttp() {
        Map<String, Object> body = given().contentType("application/json").accept("application/json").body("null")
                .when().post("/auth/login").then().statusCode(400).contentType("application/json")
                .extract().jsonPath().getMap("");
        assertEquals(Map.of("message", "Request is null"), body);
    }

    @Test
    void preservesUnexpectedErrorResponse() {
        for (String message : new String[]{"Controlled QA failure", null}) {
            var resource = new AuthResource();
            resource.authentication = new AuthenticationService(null, null, null) {
                @Override
                public AuthenticatedUser authenticate(String username, String password, String client) {
                    throw new IllegalStateException(message);
                }
            };
            var request = new LoginRequest();
            request.username = "student";
            request.password = STUDENT_PASSWORD;
            request.client = TEST_CLIENT;
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
                .body(Map.of("username", username, "password", password, "client", TEST_CLIENT))
                .when().post("/auth/login").then().statusCode(200).contentType("application/json")
            .extract().jsonPath();
        Map<String, Object> body = response.getMap("");
        assertEquals(java.util.Set.of("token", "expiresIn", "user", "debug"), body.keySet());
        assertNotNull(body.get("token"));
        assertFalse(((String) body.get("token")).isBlank());
        assertSignedToken((String) body.get("token"), username, TEST_CLIENT);
        assertEquals(3600, body.get("expiresIn"));
        assertEquals("authenticated-with-legacy-flow", body.get("debug"));
        assertEquals(Map.of("username", username, "displayName", displayName, "role", role), body.get("user"));
    }

    private void assertSignedToken(String token, String username, String client) {
        DecodedJWT decoded = JWT.require(Algorithm.HMAC256(TEST_TOKEN_SECRET)).build().verify(token);
        assertEquals(username, decoded.getSubject());
        assertEquals(client, decoded.getClaim("client").asString());
        assertNotNull(decoded.getIssuedAt());
        assertNotNull(decoded.getExpiresAt());
        assertEquals(3600, (decoded.getExpiresAt().getTime() - decoded.getIssuedAt().getTime()) / 1000);

        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
        assertFalse(payload.contains(TEST_TOKEN_SECRET), "token payload must not reveal the signing secret");

        String[] segments = token.split("\\.", -1);
        String alteredSignature = (segments[2].charAt(0) == 'A' ? "B" : "A") + segments[2].substring(1);
        String alteredToken = segments[0] + "." + segments[1] + "." + alteredSignature;
        assertThrows(JWTVerificationException.class,
                () -> JWT.require(Algorithm.HMAC256(TEST_TOKEN_SECRET)).build().verify(alteredToken));
    }

    private void assertInvalidClient(Map<String, Object> request) {
        var response = given().contentType("application/json").accept("application/json").body(request)
                .when().post("/auth/login").then().statusCode(400).contentType("application/json")
                .extract().jsonPath().getMap("");
        assertEquals(Map.of("message", "Client is required"), response);
        assertFalse(response.containsKey("token"));
    }

    private static class CapturingLogHandler extends Handler {
        private final StringBuilder messages = new StringBuilder();

        @Override
        public void publish(LogRecord record) {
            messages.append(record.getMessage()).append('\n');
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}

        String messages() {
            return messages.toString();
        }
    }

    private void assertRejected(String username, String password) {
        Map<String, Object> body = given().contentType("application/json")
            .body(Map.of("username", username, "password", password, "client", TEST_CLIENT))
            .when().post("/auth/login").then().statusCode(401).contentType("application/json")
            .extract().jsonPath().getMap("");
        assertEquals(Map.of("message", "Invalid user " + username + " or password"), body);
    }

    private void assertValidation(Map<String, ?> request, String message) {
        Map<String, Object> body = given().contentType("application/json").body(request)
            .when().post("/auth/login").then().statusCode(400).contentType("application/json")
            .extract().jsonPath().getMap("");
        assertEquals(Map.of("message", message), body);
    }
}
