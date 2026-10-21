package com.bcp.workshop.auth;

import com.auth0.jwt.JWT;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtTokenIssuerTest {
    private static final String TEST_SECRET = "fixed-clock-test-secret-at-least-32-bytes";
    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");

    @Test
    void expirationMatchesLifetimeUsingFixedClock() {
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var issuer = new JwtTokenIssuer(TEST_SECRET, clock);
        var token = JWT.decode(issuer.issue("student", "STUDENT", "test-client"));

        assertEquals(NOW, token.getIssuedAt().toInstant());
        assertEquals(NOW.plusSeconds(JwtTokenIssuer.TOKEN_LIFETIME_SECONDS), token.getExpiresAt().toInstant());
        assertEquals(JwtTokenIssuer.TOKEN_LIFETIME_SECONDS,
                token.getExpiresAt().toInstant().getEpochSecond() - clock.instant().getEpochSecond());
    }

    @Test
    void productionConstructorUsesSystemClock() {
        var issuer = new JwtTokenIssuer(TEST_SECRET);
        var token = JWT.decode(issuer.issue("student", "STUDENT", "test-client"));

        assertEquals("student", token.getSubject());
    }

    @Test
    void rejectsMissingOrBlankSigningSecret() {
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        assertThrows(IllegalStateException.class, () -> new JwtTokenIssuer(null, clock));
        assertThrows(IllegalStateException.class, () -> new JwtTokenIssuer("   ", clock));
    }
}