package com.bcp.workshop.auth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class AuthenticationService {
    private final UserRepository users;
    private final PasswordHasher passwords;
    private final TokenIssuer tokens;

    @Inject
    public AuthenticationService(UserRepository users, PasswordHasher passwords, TokenIssuer tokens) {
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
    }

    public AuthenticatedUser authenticate(String username, String password, String client) {
        UserCredentials credentials = users.findByUsername(username);
        if (credentials == null || !passwords.verify(password, credentials.passwordHash())) {
            return null;
        }
        UserRecord user = credentials.user();
        return new AuthenticatedUser(user, tokens.issue(user.username(), user.role(), client));
    }

    public record AuthenticatedUser(UserRecord user, String token) {}
}