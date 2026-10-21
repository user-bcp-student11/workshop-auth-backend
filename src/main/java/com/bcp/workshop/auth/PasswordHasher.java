package com.bcp.workshop.auth;

import jakarta.enterprise.context.ApplicationScoped;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@ApplicationScoped
public class PasswordHasher {
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String FORMAT = "pbkdf2-sha256";
    private static final String VERSION = "1";
    private static final int ITERATIONS = 600_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final int MAX_ENCODED_LENGTH = 256;
    private static final int FIELD_COUNT = 5;
    private final SecureRandom random = new SecureRandom();

    public String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] derived = derive(password, salt);
        return String.join("$", FORMAT, VERSION, Integer.toString(ITERATIONS),
                Base64.getEncoder().encodeToString(salt), Base64.getEncoder().encodeToString(derived));
    }

    public boolean verify(String password, String encoded) {
        if (password == null || encoded == null || encoded.length() > MAX_ENCODED_LENGTH) {
            return false;
        }
        String[] fields = encoded.split("\\$", -1);
        if (fields.length != FIELD_COUNT || !FORMAT.equals(fields[0]) || !VERSION.equals(fields[1])
                || !Integer.toString(ITERATIONS).equals(fields[2])) {
            return false;
        }
        try {
            byte[] salt = Base64.getDecoder().decode(fields[3]);
            byte[] expected = Base64.getDecoder().decode(fields[4]);
            if (salt.length != SALT_BYTES || expected.length != HASH_BYTES) {
                return false;
            }
            return MessageDigest.isEqual(expected, derive(password, salt));
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private byte[] derive(String password, byte[] salt) {
        char[] characters = password.toCharArray();
        var specification = new PBEKeySpec(characters, salt, ITERATIONS, HASH_BYTES * Byte.SIZE);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(specification).getEncoded();
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Password derivation unavailable");
        } finally {
            specification.clearPassword();
            Arrays.fill(characters, '\0');
        }
    }
}