package com.mineledger.backend.auth;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Where the browser hands the result of a sign-in back to the game. */
public interface CallbackReceiver {
    interface Listening extends AutoCloseable {
        /** The exact redirect address to send to the identity provider. */
        URI redirectUri();

        /** The query parameters of the one redirect that carried this attempt's {@code state}. */
        CompletableFuture<Map<String, String>> callback();

        /** Stops listening. Safe to call more than once. */
        @Override void close();
    }

    /**
     * Starts listening for one redirect.
     *
     * @throws AuthException if nothing can listen (for example, every allowed port is in use)
     */
    Listening start(String expectedState);
}
