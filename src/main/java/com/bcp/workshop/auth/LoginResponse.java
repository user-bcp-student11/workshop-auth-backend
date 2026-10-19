package com.bcp.workshop.auth;

import org.apache.commons.text.StringEscapeUtils;

public record LoginResponse(String token, int expiresIn, User user, String debug) {
    private static final int TOKEN_LIFETIME_SECONDS = 3600;
    private static final String LEGACY_FLOW = "authenticated-with-legacy-flow";

    static LoginResponse from(AuthenticationService.AuthenticatedUser authenticated) {
        UserRecord user = authenticated.user();
        return new LoginResponse(authenticated.token(), TOKEN_LIFETIME_SECONDS,
                new User(user.username(), StringEscapeUtils.escapeHtml4(user.displayName()), user.role()), LEGACY_FLOW);
    }

    public record User(String username, String displayName, String role) {}
}