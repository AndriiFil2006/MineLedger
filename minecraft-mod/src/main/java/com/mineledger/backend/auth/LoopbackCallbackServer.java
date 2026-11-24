package com.mineledger.backend.auth;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Receives the browser's redirect at the end of a sign-in (RFC 8252 loopback redirection).
 *
 * <p>It listens on 127.0.0.1 only, so no other computer can connect, and only for as long as one sign-in
 * is waiting. It answers exactly one kind of request: {@code GET /callback} carrying the {@code state}
 * this attempt generated. Anything else gets an error page and is otherwise ignored, so another program
 * or web page cannot end or hijack the attempt without knowing that value. Nothing from the request is
 * echoed into a page.
 *
 * <p>The ports are fixed because the identity provider must be told the exact redirect addresses in
 * advance. If the first is busy the next is tried.
 */
public final class LoopbackCallbackServer implements CallbackReceiver {
    /** Each needs a matching Allowed Callback URL at the identity provider; see backend/AUTHENTICATION.md. */
    public static final List<Integer> DEFAULT_PORTS = List.of(47821, 47822, 47823);
    public static final String PATH = "/callback";
    private static final int MAX_REQUEST_BYTES = 16 * 1024;
    private static final int READ_TIMEOUT_MILLIS = 5000;
    private static final AtomicInteger THREADS = new AtomicInteger();

    private final List<Integer> ports;

    public LoopbackCallbackServer() { this(DEFAULT_PORTS); }

    /** @param ports tried in order; 0 means any free port (tests) */
    public LoopbackCallbackServer(List<Integer> ports) { this.ports = List.copyOf(ports); }

    @Override
    public Listening start(String expectedState) {
        if (expectedState == null || expectedState.isBlank()) throw new IllegalArgumentException("A state value is required");
        for (int port : ports) {
            ServerSocket socket = null;
            try {
                socket = new ServerSocket();
                socket.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), port), 16);
                return new Session(socket, expectedState);
            } catch (IOException exception) {
                closeQuietly(socket);
            }
        }
        throw new AuthException(AuthException.Kind.NOT_AVAILABLE, "Sign-in could not start because the ports it needs ("
                + ports.stream().map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse("none")
                + ") are in use on this computer. Close the program using them and try again.");
    }

    private static void closeQuietly(ServerSocket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) {
            // Nothing useful can be done about a socket that will not close.
        }
    }

    private static final class Session implements Listening {
        private final ServerSocket socket;
        private final String expectedState;
        private final URI redirectUri;
        private final CompletableFuture<Map<String, String>> callback = new CompletableFuture<>();

        Session(ServerSocket socket, String expectedState) {
            this.socket = socket;
            this.expectedState = expectedState;
            redirectUri = URI.create("http://127.0.0.1:" + socket.getLocalPort() + PATH);
            daemon(this::acceptLoop, "MineLedger-SignIn-" + THREADS.incrementAndGet()).start();
        }

        @Override public URI redirectUri() { return redirectUri; }
        @Override public CompletableFuture<Map<String, String>> callback() { return callback; }

        @Override
        public void close() {
            closeQuietly(socket);
            callback.cancel(false);
        }

        private void acceptLoop() {
            while (!socket.isClosed()) {
                Socket connection;
                try {
                    connection = socket.accept();
                } catch (IOException exception) {
                    return; // closed
                }
                daemon(() -> serve(connection), "MineLedger-SignIn-Request-" + THREADS.incrementAndGet()).start();
            }
        }

        private void serve(Socket connection) {
            try (connection) {
                connection.setSoTimeout(READ_TIMEOUT_MILLIS);
                String target = readTarget(connection.getInputStream());
                Map<String, String> parameters = target == null ? null : callbackParameters(target);
                boolean ours = parameters != null && sameText(expectedState, parameters.get("state"))
                        && (parameters.containsKey("code") || parameters.containsKey("error"));
                boolean succeeded = ours && parameters.containsKey("code") && !parameters.containsKey("error");
                if (!ours) {
                    respond(connection.getOutputStream(), "404 Not Found", "Nothing to see here",
                            "This address is only used while MineLedger is signing in.");
                    return;
                }
                respond(connection.getOutputStream(), "200 OK", succeeded ? "You are signed in" : "Sign-in was not completed",
                        succeeded ? "You can close this tab and return to Minecraft."
                                : "Return to Minecraft to see what happened and try again.");
                if (callback.complete(Map.copyOf(parameters))) closeQuietly(socket);
            } catch (IOException | RuntimeException ignored) {
                // A browser that hangs up or sends nonsense does not end the sign-in.
            }
        }

        /** The request target of {@code GET <target> HTTP/1.x}, after reading the whole header block; else null. */
        private static String readTarget(InputStream input) throws IOException {
            byte[] buffer = new byte[MAX_REQUEST_BYTES];
            int length = 0;
            while (length < buffer.length) {
                int read = input.read(buffer, length, buffer.length - length);
                if (read < 0) break;
                length += read;
                if (endsHeaders(buffer, length)) break;
            }
            String head = new String(buffer, 0, length, StandardCharsets.ISO_8859_1);
            int lineEnd = head.indexOf("\r\n");
            if (lineEnd < 0 || !endsHeaders(buffer, length)) return null;
            String[] parts = head.substring(0, lineEnd).split(" ");
            if (parts.length != 3 || !parts[0].equals("GET") || !parts[2].startsWith("HTTP/1.")) return null;
            return parts[1];
        }

        private static boolean endsHeaders(byte[] buffer, int length) {
            return length >= 4 && buffer[length - 4] == '\r' && buffer[length - 3] == '\n'
                    && buffer[length - 2] == '\r' && buffer[length - 1] == '\n';
        }

        private static Map<String, String> callbackParameters(String target) {
            int question = target.indexOf('?');
            if (question < 0 || !target.substring(0, question).equals(PATH)) return null;
            Map<String, String> parameters = new HashMap<>();
            for (String pair : target.substring(question + 1).split("&")) {
                int equals = pair.indexOf('=');
                if (equals <= 0) continue;
                try {
                    // The first value wins: a duplicated parameter is never allowed to replace it.
                    parameters.putIfAbsent(URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                            URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
                } catch (IllegalArgumentException exception) {
                    return null;
                }
            }
            return parameters;
        }

        private static boolean sameText(String expected, String actual) {
            return actual != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
        }

        private static void respond(OutputStream output, String status, String heading, String message) throws IOException {
            byte[] body = ("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>MineLedger</title></head>"
                    + "<body style=\"font-family:sans-serif;text-align:center;margin-top:15vh\"><h1>" + heading + "</h1><p>"
                    + message + "</p></body></html>").getBytes(StandardCharsets.UTF_8);
            output.write(("HTTP/1.1 " + status + "\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + body.length
                    + "\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff"
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            output.write(body);
            output.flush();
        }

        private static Thread daemon(Runnable task, String name) {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        }
    }
}
