package com.bcp.workshop.auth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class UserRepository {
    private static final Logger LOG = Logger.getLogger(UserRepository.class);
    private static final String FIND_CREDENTIALS = "SELECT id, username, password_hash, display_name, role FROM legacy_users WHERE username = ?";
    private final LegacyDatabase database;

    @Inject
    public UserRepository(LegacyDatabase database) {
        this.database = database;
    }

    UserCredentials findByUsername(String username) {
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement(FIND_CREDENTIALS)) {
            statement.setString(1, username);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                var user = new UserRecord(result.getLong("id"), result.getString("username"),
                    result.getString("display_name"), result.getString("role"));
                return new UserCredentials(user, result.getString("password_hash"));
            }
        } catch (Exception error) {
            LOG.error("Unable to query authentication storage");
            return null;
        }
    }
}