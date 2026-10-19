package com.bcp.workshop.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UserRepositoryTest {
    private Path directory;
    private Path file;
    private java.sql.Driver driver;
    private LegacyDatabase database;

    @BeforeEach
    void setUp() throws Exception {
        directory = Files.createTempDirectory(Path.of("target"), "qa-query-");
        file = directory.resolve("users.db");
        driver = new org.sqlite.JDBC();
        DriverManager.registerDriver(driver);
        database = new LegacyDatabase("jdbc:sqlite:" + file, new PasswordHasher(), "Workshop123!", "Trainer123!");
        database.initialize();
    }

    @AfterEach
    void cleanUp() throws Exception {
        DriverManager.deregisterDriver(driver);
        Files.deleteIfExists(file);
        Files.deleteIfExists(directory);
    }

    @Test
    void queryFailureRemainsInvalidCredentials() throws Exception {
        try (var connection = database.openConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE legacy_users");
        }
        var repository = new UserRepository(database);
        assertNull(repository.findByUsername("student"));
        var resource = new AuthResource();
        resource.authentication = new AuthenticationService(repository);
        var request = new LoginRequest();
        request.username = "student";
        request.password = "incorrect";
        try (var response = resource.login(request)) {
            assertEquals(401, response.getStatus());
        }
    }

    @Test
    void findsOnlyTheExactUsername() {
        var repository = new UserRepository(database);
        assertEquals("student", repository.findByUsername("student").user().username());
        assertNull(repository.findByUsername("missing-user"));
        assertNull(repository.findByUsername("student' --"));
        assertNull(new AuthenticationService(repository).authenticate("missing-user", "incorrect"));
    }
}