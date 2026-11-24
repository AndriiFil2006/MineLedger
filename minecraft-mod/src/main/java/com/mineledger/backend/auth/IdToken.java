package com.mineledger.backend.auth;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Reads the display name from an ID token, after checking that the token belongs to this sign-in attempt.
 *
 * <p>The signature is not verified here, which OpenID Connect Core 3.1.3.7 permits for a token received
 * directly from the token endpoint over TLS. That is safe only because nothing is decided from it: the
 * name is shown to the player and that is all. Who the player is, and their role, come from the
 * MineLedger server, which verifies the access token's signature itself.
 */
public final class IdToken {
    private static final long CLOCK_SKEW_SECONDS = 60;
    private static final int MAX_NAME_LENGTH = 60;

    /**
     * @return the {@code name} (or {@code nickname}) claim, if any
     * @throws AuthException if the token is unreadable, expired, or was issued for another issuer, client or attempt
     */
    public static Optional<String> displayName(String idToken, AuthSettings settings, String expectedNonce, long nowMillis) {
        JsonObject claims;
        try {
            String[] parts = idToken.split("\\.", -1);
            if (parts.length != 3) throw new IllegalArgumentException("not a signed token");
            claims = JsonParser.parseString(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw AuthException.protocol("the sign-in service sent an identity this game cannot read.");
        }
        if (!settings.issuer().equals(text(claims, "iss").orElse(null))) throw AuthException.protocol("the identity came from a different sign-in service.");
        if (!audienceIncludes(claims.get("aud"), settings.clientId())) throw AuthException.protocol("the identity was issued for a different application.");
        if (!expectedNonce.equals(text(claims, "nonce").orElse(null))) throw AuthException.protocol("the identity does not belong to this sign-in attempt.");
        long expires;
        try {
            expires = claims.get("exp").getAsBigDecimal().longValue();
        } catch (RuntimeException exception) {
            throw AuthException.protocol("the identity has no expiry.");
        }
        if (expires + CLOCK_SKEW_SECONDS < nowMillis / 1000) throw AuthException.protocol("the identity has already expired.");
        return text(claims, "name").or(() -> text(claims, "nickname")).map(name -> printable(name, MAX_NAME_LENGTH)).filter(name -> !name.isEmpty());
    }

    private static boolean audienceIncludes(JsonElement audience, String clientId) {
        if (audience == null) return false;
        if (audience.isJsonArray()) {
            for (JsonElement item : audience.getAsJsonArray()) {
                if (item.isJsonPrimitive() && clientId.equals(item.getAsString())) return true;
            }
            return false;
        }
        return audience.isJsonPrimitive() && clientId.equals(audience.getAsString());
    }

    private static Optional<String> text(JsonObject claims, String key) {
        JsonElement value = claims.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return Optional.empty();
        return Optional.of(value.getAsString());
    }

    /**
     * One short plain line. The text was chosen by someone else (a person's name, a provider's message), so
     * control characters and Minecraft formatting codes are dropped and the length is capped.
     */
    static String printable(String name, int maxLength) {
        String clean = name.codePoints().filter(point -> !Character.isISOControl(point) && point != '§'
                        && Character.getType(point) != Character.FORMAT)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString().strip();
        return clean.length() > maxLength ? clean.substring(0, maxLength) + "..." : clean;
    }

    private IdToken() { }
}
