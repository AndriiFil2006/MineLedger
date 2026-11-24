package com.mineledger.backend.auth;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * A sign-in step that failed. Messages are written for the player and never contain a token, an
 * authorization code or a response body.
 */
public final class AuthException extends RuntimeException {
    public enum Kind {
        /** The server has no sign-in set up, or its settings cannot be used safely. */
        NOT_AVAILABLE,
        /** The identity provider could not be reached or did not answer in time. Trying again may work. */
        NETWORK,
        /** The identity provider refused: the sign-in was denied, or a stored session is no longer valid. */
        REJECTED,
        /** An answer that does not follow the protocol, or does not belong to this sign-in attempt. */
        PROTOCOL,
        /** Nobody finished signing in within the time allowed. */
        TIMED_OUT
    }

    private final Kind kind;

    public AuthException(Kind kind, String message) { this(kind, message, null); }

    public AuthException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() { return kind; }

    /** The underlying failure, without the wrappers futures add. */
    public static Throwable unwrap(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    static AuthException network(Throwable cause) {
        boolean timeout = cause instanceof TimeoutException || cause instanceof java.net.http.HttpTimeoutException;
        return new AuthException(Kind.NETWORK, timeout ? "The sign-in service did not respond in time."
                : "Cannot reach the sign-in service. Check your internet connection.", cause);
    }

    static AuthException protocol(String message) {
        return new AuthException(Kind.PROTOCOL, "Sign-in failed: " + message);
    }
}
