package com.bcp.workshop.auth;

import org.junit.jupiter.api.Test;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class PasswordHasherTest {
    private final PasswordHasher passwords = new PasswordHasher();

    @Test
    void verifiesCorrectPasswordAndRejectsIncorrectPassword() {
        String encoded = passwords.hash("QA password");
        assertTrue(passwords.verify("QA password", encoded));
        assertFalse(passwords.verify("wrong", encoded));
        assertFalse(passwords.verify("QA password ", encoded));
    }

    @Test
    void usesIndependentSaltForIdenticalPasswords() {
        String first = passwords.hash("Same password");
        String second = passwords.hash("Same password");
        assertNotEquals(first, second);
        assertTrue(passwords.verify("Same password", second));
    }

    @Test
    void matchesJdkPbkdf2WithExplicitParameters() throws Exception {
        long started = System.nanoTime();
        String encoded = passwords.hash("Interoperability fixture");
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        String[] fields = encoded.split("\\$");
        assertEquals("pbkdf2-sha256", fields[0]);
        assertEquals("1", fields[1]);
        assertEquals("600000", fields[2]);
        byte[] salt = Base64.getDecoder().decode(fields[3]);
        assertEquals(16, salt.length);
        var specification = new PBEKeySpec("Interoperability fixture".toCharArray(), salt, 600_000, 256);
        try {
            byte[] expected = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(specification).getEncoded();
            assertArrayEquals(expected, Base64.getDecoder().decode(fields[4]));
        } finally {
            specification.clearPassword();
        }
        System.out.println("QA PBKDF2 600000 iterations, one derivation milliseconds: " + elapsed);
    }

    @Test
    void rejectsNullOversizedAndMalformedRepresentations() {
        assertFalse(passwords.verify(null, "encoded"));
        assertFalse(passwords.verify("password", null));
        assertFalse(passwords.verify("password", "x".repeat(257)));
        for (String invalid : new String[]{"", "plain-password", "other$1$600000$salt$hash",
                "pbkdf2-sha256$2$600000$salt$hash", "pbkdf2-sha256$1$999999999$salt$hash",
                "pbkdf2-sha256$1$600000$!invalid!$!invalid!", "pbkdf2-sha256$1$600000$AA==$AA==",
                "pbkdf2-sha256$1$600000$AAAAAAAAAAAAAAAAAAAAAA==$AA=="}) {
            assertFalse(passwords.verify("password", invalid));
        }
    }
}