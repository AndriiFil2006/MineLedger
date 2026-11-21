package com.mineledger.backend;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/** Every backend failure, classified so callers never inspect transport exceptions. Messages are safe to display. */
public final class BackendException extends RuntimeException {
    public enum Kind { UNREACHABLE, TIMEOUT, HTTP_STATUS, MALFORMED_RESPONSE }

    private final Kind kind;
    private final int status;

    public BackendException(Kind kind, String message) { this(kind, 0, message, null); }

    public BackendException(Kind kind, int status, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.status = status;
    }

    public Kind kind() { return kind; }

    /** HTTP status for {@link Kind#HTTP_STATUS}, otherwise 0. */
    public int status() { return status; }

    /** True when the server answered and refused this particular request (4xx): the backend itself is reachable. */
    public boolean backendReachable() { return kind == Kind.HTTP_STATUS && status >= 400 && status < 500; }

    public static BackendException malformed(String message) {
        return new BackendException(Kind.MALFORMED_RESPONSE, "Unexpected backend response: " + message);
    }

    /** Unwraps future wrappers and classifies transport failures. */
    public static BackendException from(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof BackendException known) return known;
        if (cause instanceof HttpTimeoutException || cause instanceof TimeoutException) {
            return new BackendException(Kind.TIMEOUT, 0, "The backend did not respond in time.", cause);
        }
        if (cause instanceof IOException || cause instanceof CancellationException) {
            return new BackendException(Kind.UNREACHABLE, 0, "Cannot reach the backend. Is the server running?", cause);
        }
        return new BackendException(Kind.UNREACHABLE, 0,
                "Backend request failed (" + cause.getClass().getSimpleName() + ").", cause);
    }
}
