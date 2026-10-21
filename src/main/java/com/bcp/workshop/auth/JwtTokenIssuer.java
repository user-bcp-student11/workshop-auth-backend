package com.bcp.workshop.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

@ApplicationScoped
public class JwtTokenIssuer implements TokenIssuer {
    static final int TOKEN_LIFETIME_SECONDS = 3600;

    private final String secret;
    private final Clock clock;

    @Inject
    public JwtTokenIssuer(@ConfigProperty(name = "auth.token.secret") String secret) {
        this(secret, Clock.systemUTC());
    }

    JwtTokenIssuer(String secret, Clock clock) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("AUTH_TOKEN_SECRET must be configured");
        }
        this.secret = secret;
        this.clock = clock;
    }

    @Override
    public String issue(String username, String role, String client) {
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plusSeconds(TOKEN_LIFETIME_SECONDS);
        return JWT.create()
                .withSubject(username)
                .withClaim("client", client)
                .withIssuedAt(Date.from(issuedAt))
                .withExpiresAt(Date.from(expiresAt))
                .sign(Algorithm.HMAC256(secret));
    }
}