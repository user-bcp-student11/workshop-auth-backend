package com.bcp.workshop.auth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class AuthenticationService {
    private final UserRepository users;
    private final PasswordHasher passwords;

    @Inject
    public AuthenticationService(UserRepository users, PasswordHasher passwords) {
        this.users = users;
        this.passwords = passwords;
    }

    AuthenticationService(UserRepository users) {
        this(users, new PasswordHasher());
    }

    public AuthenticatedUser authenticate(String username, String password) {
        UserCredentials credentials = users.findByUsername(username);
        if (credentials == null || !passwords.verify(password, credentials.passwordHash())) {
            return null;
        }
        UserRecord user = credentials.user();
        return new AuthenticatedUser(user, LegacyTokenUtil.create(user.username(), user.role()));
    }

    public record AuthenticatedUser(UserRecord user, String token) {}
}