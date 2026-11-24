package com.mineledger.backend.auth;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The MineLedger server's account operations. Asynchronous like {@code CurriculumApi}: calls return at once
 * and futures fail with a {@code BackendException}.
 */
public interface AccountApi {
    /** A signed-in person as the server knows them. The role is the server's, never the client's. */
    record RemoteUser(String id, Role role) { }

    enum Role {
        STUDENT("Student"), TEACHER("Teacher");

        private final String label;
        Role(String label) { this.label = label; }
        public String label() { return label; }
    }

    /** Empty when the server has no sign-in configured. Public: sends no credentials. */
    CompletableFuture<Optional<AuthSettings>> authSettings();

    /** {@code GET /api/v1/users/me} with this access token. The token is sent to the MineLedger server only. */
    CompletableFuture<RemoteUser> me(String accessToken);
}
