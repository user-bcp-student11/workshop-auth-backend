package com.bcp.workshop.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;

class LegacyDatabaseTest {
    private Path directory;
    private String databaseUrl;
    private LegacyDatabase database;
    private java.sql.Driver sqliteDriver;

    @BeforeEach
    void setUp() throws Exception {
        sqliteDriver = new org.sqlite.JDBC();
        DriverManager.registerDriver(sqliteDriver);
        directory = Files.createTempDirectory(Path.of("target"), "qa-storage-");
        databaseUrl = "jdbc:sqlite:" + directory.resolve("users.db");
        database = new LegacyDatabase(databaseUrl, new PasswordHasher(), "Workshop123!", "Trainer123!");
    }

    @AfterEach
    void cleanUp() throws Exception {
        DriverManager.deregisterDriver(sqliteDriver);
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void initializesBothLaboratoryUsers() throws Exception {
        database.initialize();
        try (var connection = DriverManager.getConnection(databaseUrl);
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT username, display_name, role FROM legacy_users ORDER BY username")) {
            assertTrue(result.next());
            assertEquals("student", result.getString("username"));
            assertEquals("Workshop Student", result.getString("display_name"));
            assertEquals("STUDENT", result.getString("role"));
            assertTrue(result.next());
            assertEquals("trainer", result.getString("username"));
            assertEquals("TRAINER", result.getString("role"));
            assertFalse(result.next());
        }
    }

    @Test
    void preservesExistingUsersAndMetadataAcrossRestart() throws Exception {
        createLegacyFixture("PersonalPassword!");
        database.initialize();
        database.initialize();
        try (var connection = DriverManager.getConnection(databaseUrl);
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT id, username, display_name, role FROM legacy_users WHERE id = 41")) {
            assertTrue(result.next());
            assertEquals(41, result.getLong("id"));
            assertEquals("student", result.getString("username"));
            assertEquals("Personal Student", result.getString("display_name"));
            assertEquals("CUSTOM_ROLE", result.getString("role"));
        }
        assertNotNull(authenticationService().authenticate("student", "PersonalPassword!", "test-client"));
        assertNull(authenticationService().authenticate("student", "Workshop123!", "test-client"));
    }

    @Test
    void preservesUnicodeAndLongPasswords() throws Exception {
        String password = "\u00e1\u6f22\ud83d\udd12" + "long-password".repeat(40);
        createLegacyFixture(password);
        database.initialize();
        var authentication = authenticationService();
        assertNotNull(authentication.authenticate("student", password, "test-client"));
        assertNull(authentication.authenticate("student", password + " ", "test-client"));
    }

    @Test
    void migrationRemovesPlaintextAndIsIdempotent() throws Exception {
        createLegacyFixture("PersonalPassword!");
        database.initialize();
        String firstHash = readHash();
        assertNotEquals("PersonalPassword!", firstHash);
        database.initialize();
        assertEquals(firstHash, readHash());
        try (var connection = DriverManager.getConnection(databaseUrl);
             var statement = connection.createStatement();
             var result = statement.executeQuery("PRAGMA table_info(legacy_users)")) {
            while (result.next()) {
                assertNotEquals("password", result.getString("name"));
            }
        }
    }

    @Test
    void failedMigrationRollsBackAndDoesNotAuthenticatePlaintext() throws Exception {
        createLegacyFixture("PersonalPassword!");
        try (var connection = DriverManager.getConnection(databaseUrl);
             var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE VIEW migration_guard AS SELECT password FROM legacy_users");
        }
        database.initialize();
        try (var connection = DriverManager.getConnection(databaseUrl);
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT password FROM legacy_users WHERE id = 41")) {
            assertTrue(result.next());
            assertEquals("PersonalPassword!", result.getString(1));
        }
        assertTrue(authenticationService().authenticate("student", "PersonalPassword!", "test-client") == null,
            "A failed migration must not enable plaintext authentication");
    }

    @Test
    void migrationCleansPlaintextFromActiveDatabaseAndWal() throws Exception {
        createLegacyFixture("PlaintextStorageMarker!");
        try (var connection = DriverManager.getConnection(databaseUrl); var statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
        }
        database.initialize();
        assertNotNull(authenticationService().authenticate("student", "PlaintextStorageMarker!", "test-client"));
        try (var paths = Files.list(directory)) {
            for (Path file : paths.toList()) {
                String contents = new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.ISO_8859_1);
                assertFalse(contents.contains("PlaintextStorageMarker!"));
            }
        }
    }

    @Test
    void rejectsUnknownSchemaAndFutureVersionWithoutFallback() throws Exception {
        try (var connection = DriverManager.getConnection(databaseUrl); var statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = 99");
        }
        database.initialize();
        assertThrows(java.sql.SQLException.class, database::openConnection);
        try (var connection = DriverManager.getConnection(databaseUrl); var statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = 0");
            statement.execute("CREATE TABLE legacy_users (id INTEGER)");
        }
        database.initialize();
        assertThrows(java.sql.SQLException.class, database::openConnection);
    }

    @Test
    void missingSeedConfigurationDoesNotPersistBlankPasswords() throws Exception {
        database = new LegacyDatabase(databaseUrl, new PasswordHasher(), null, "   ");
        database.initialize();
        try (var connection = database.openConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT count(*) FROM legacy_users")) {
            assertTrue(result.next());
            assertEquals(0, result.getInt(1));
        }
    }

    @Test
    void rejectsMalformedStoredHashWithoutThrowing() throws Exception {
        database.initialize();
        try (var connection = database.openConnection(); var statement = connection.prepareStatement(
                "UPDATE legacy_users SET password_hash = ? WHERE username = ?")) {
            statement.setString(1, "invalid hash");
            statement.setString(2, "student");
            statement.executeUpdate();
        }
        assertNull(authenticationService().authenticate("student", "Workshop123!", "test-client"));
    }

    private AuthenticationService authenticationService() {
        return new AuthenticationService(new UserRepository(database), new PasswordHasher(),
                (username, role, client) -> "qa-token-fixture");
    }

    private String readHash() throws Exception {
        try (var connection = DriverManager.getConnection(databaseUrl);
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT password_hash FROM legacy_users WHERE id = 41")) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private void createLegacyFixture(String password) throws Exception {
        try (var connection = DriverManager.getConnection(databaseUrl);
             var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE legacy_users (id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT UNIQUE, password TEXT NOT NULL, display_name TEXT NOT NULL, role TEXT NOT NULL)");
            insertLegacyUser(connection, password);
        }
    }

    private void insertLegacyUser(Connection connection, String password) throws Exception {
        try (var statement = connection.prepareStatement("INSERT INTO legacy_users VALUES (?,?,?,?,?)")) {
            statement.setLong(1, 41);
            statement.setString(2, "student");
            statement.setString(3, password);
            statement.setString(4, "Personal Student");
            statement.setString(5, "CUSTOM_ROLE");
            statement.executeUpdate();
        }
    }
}