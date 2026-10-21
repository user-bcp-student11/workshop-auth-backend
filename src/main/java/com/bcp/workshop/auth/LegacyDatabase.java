package com.bcp.workshop.auth;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import org.eclipse.microprofile.config.ConfigProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

@ApplicationScoped
public class LegacyDatabase {
    private static final Logger LOG = Logger.getLogger(LegacyDatabase.class);
    private static final int SCHEMA_VERSION = 1;
    private static final String USER_COLUMNS = "(id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT UNIQUE, password_hash TEXT NOT NULL, display_name TEXT NOT NULL, role TEXT NOT NULL)";
    private static final String CREATE_USERS = "CREATE TABLE IF NOT EXISTS legacy_users " + USER_COLUMNS;
    private static final String CREATE_REPLACEMENT = "CREATE TABLE legacy_users_migrating " + USER_COLUMNS;
    private static final String INSERT_USER = "INSERT OR IGNORE INTO legacy_users(username,password_hash,display_name,role) VALUES (?,?,?,?)";
    private static final String COPY_USER = "INSERT INTO legacy_users_migrating(id,username,password_hash,display_name,role) VALUES (?,?,?,?,?)";
    private final String databaseUrl;
    private final PasswordHasher passwords;
    private final String studentPassword;
    private final String trainerPassword;
    private volatile boolean available;

    public LegacyDatabase() {
        this(ConfigProvider.getConfig().getValue("auth.database.url", String.class));
    }

    LegacyDatabase(String databaseUrl) {
        this(databaseUrl, new PasswordHasher(), configuredPassword("auth.seed.student-password"),
                configuredPassword("auth.seed.trainer-password"));
    }

    LegacyDatabase(String databaseUrl, PasswordHasher passwords, String studentPassword, String trainerPassword) {
        this.databaseUrl = databaseUrl;
        this.passwords = passwords;
        this.studentPassword = studentPassword;
        this.trainerPassword = trainerPassword;
    }

    @PostConstruct
    void initialize() {
        available = false;
        try {
            Files.createDirectories(Path.of("data"));
            try (Connection connection = DriverManager.getConnection(databaseUrl)) {
                execute(connection, "PRAGMA secure_delete = ON");
                initializeTransaction(connection);
                execute(connection, "PRAGMA wal_checkpoint(TRUNCATE)");
                execute(connection, "VACUUM");
                available = true;
            }
        } catch (Exception error) {
            LOG.error("Unable to initialize authentication storage");
        }
    }

    Connection openConnection() throws SQLException {
        if (!available) {
            throw new SQLException("Authentication storage unavailable");
        }
        return DriverManager.getConnection(databaseUrl);
    }

    private static String configuredPassword(String property) {
        return ConfigProvider.getConfig().getOptionalValue(property, String.class).orElse(null);
    }

    private void initializeTransaction(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        try {
            migrateSchema(connection);
            insertUser(connection, "student", studentPassword, "Workshop Student", "STUDENT");
            insertUser(connection, "trainer", trainerPassword, "Workshop Trainer", "TRAINER");
            execute(connection, "PRAGMA user_version = " + SCHEMA_VERSION);
            connection.commit();
        } catch (SQLException | RuntimeException error) {
            connection.rollback();
            throw error;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private void migrateSchema(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery("PRAGMA user_version")) {
            if (result.next() && result.getInt(1) > SCHEMA_VERSION) {
                throw new SQLException("Unsupported authentication schema version");
            }
        }
        Set<String> columns = columns(connection);
        if (columns.isEmpty()) {
            execute(connection, CREATE_USERS);
            return;
        }
        if (columns.contains("password")) {
            migratePlaintext(connection);
            return;
        }
        if (!columns.contains("password_hash")) {
            throw new SQLException("Unsupported authentication schema");
        }
    }

    private Set<String> columns(Connection connection) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (var statement = connection.createStatement(); var result = statement.executeQuery("PRAGMA table_info(legacy_users)")) {
            while (result.next()) {
                columns.add(result.getString("name"));
            }
        }
        return columns;
    }

    private void migratePlaintext(Connection connection) throws SQLException {
        execute(connection, CREATE_REPLACEMENT);
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT id,username,password,display_name,role FROM legacy_users");
             var replacement = connection.prepareStatement(COPY_USER)) {
            while (result.next()) {
                replacement.setLong(1, result.getLong("id"));
                replacement.setString(2, result.getString("username"));
                replacement.setString(3, passwords.hash(result.getString("password")));
                replacement.setString(4, result.getString("display_name"));
                replacement.setString(5, result.getString("role"));
                replacement.executeUpdate();
            }
        }
        execute(connection, "DROP TABLE legacy_users");
        execute(connection, "ALTER TABLE legacy_users_migrating RENAME TO legacy_users");
    }

    private void insertUser(Connection connection, String username, String password, String displayName, String role) throws SQLException {
        if (password == null || password.isBlank() || userExists(connection, username)) {
            return;
        }
        try (var statement = connection.prepareStatement(INSERT_USER)) {
            statement.setString(1, username);
            statement.setString(2, passwords.hash(password));
            statement.setString(3, displayName);
            statement.setString(4, role);
            statement.executeUpdate();
        }
    }

    private boolean userExists(Connection connection, String username) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT 1 FROM legacy_users WHERE username = ?")) {
            statement.setString(1, username);
            try (var result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
