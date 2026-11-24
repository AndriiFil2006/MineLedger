package com.mineledger.backend.auth;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Where and as which public OAuth client a player signs in, as published by the MineLedger server
 * ({@code GET /api/v1/auth/config}). None of it is secret; the mod has no client secret.
 */
public record AuthSettings(String issuer, String clientId, String audience) {
    public AuthSettings {
        if (issuer == null || issuer.isBlank() || clientId == null || clientId.isBlank() || audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("issuer, client ID and audience are required");
        }
        if (!secure(issuer)) throw new IllegalArgumentException("the sign-in service must use https");
    }

    /**
     * True for https, and for plain http to this computer only (local development and tests). Tokens and
     * authorization codes are never sent anywhere else without TLS.
     */
    public static boolean secure(String url) {
        try {
            return secure(new URI(url));
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    public static boolean secure(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (host == null || host.isBlank() || uri.getRawUserInfo() != null) return false;
        if (scheme.equals("https")) return true;
        return scheme.equals("http") && loopback(host);
    }

    private static boolean loopback(String host) {
        String name = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (name.equalsIgnoreCase("localhost")) return true;
        // Literal addresses only: a name is never resolved here, so DNS cannot make a remote host look local.
        if (!name.matches("[0-9.]+") && !name.contains(":")) return false;
        try {
            return InetAddress.getByName(name).isLoopbackAddress();
        } catch (java.net.UnknownHostException | RuntimeException exception) {
            return false;
        }
    }
}
