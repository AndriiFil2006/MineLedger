package com.mineledger.backend.auth;

import java.net.URI;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The identity provider's back-channel operations for a public client: discovery, the authorization-code
 * and refresh-token grants, and refresh-token revocation. Futures fail with an {@link AuthException}.
 */
public interface OidcProvider {
    /** Endpoints from the provider's discovery document. */
    record Metadata(URI authorizationEndpoint, URI tokenEndpoint, Optional<URI> revocationEndpoint) { }

    /** A token response. {@code toString} is redacted so a token cannot reach a log through this record. */
    record Tokens(String accessToken, long expiresInSeconds, Optional<String> refreshToken, Optional<String> idToken) {
        @Override public String toString() {
            return "Tokens[expiresInSeconds=" + expiresInSeconds + ", refreshToken=" + (refreshToken.isPresent() ? "present" : "absent") + "]";
        }
    }

    CompletableFuture<Metadata> discover(String issuer);

    CompletableFuture<Tokens> exchangeCode(Metadata metadata, String clientId, String code, URI redirectUri, String codeVerifier);

    CompletableFuture<Tokens> refresh(Metadata metadata, String clientId, String refreshToken);

    /** Best effort: completes normally whether or not the provider revoked anything. */
    CompletableFuture<Void> revoke(Metadata metadata, String clientId, String refreshToken);
}
