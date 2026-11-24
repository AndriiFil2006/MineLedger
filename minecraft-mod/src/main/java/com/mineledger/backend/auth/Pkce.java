package com.mineledger.backend.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** Proof Key for Code Exchange (RFC 7636), S256 only, plus the random values a sign-in attempt needs. */
public record Pkce(String verifier, String challenge) {
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 32 random bytes as 43 URL-safe characters: a code verifier, a {@code state} or a {@code nonce}. */
    public static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static Pkce fromVerifier(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return new Pkce(verifier, Base64.getUrlEncoder().withoutPadding().encodeToString(digest));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", exception);
        }
    }

    @Override public String toString() { return "Pkce[challenge=" + challenge + "]"; }
}
