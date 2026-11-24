package com.mineledger.backend.auth;

import static org.junit.Assert.*;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Test;
import com.mineledger.backend.auth.CallbackReceiver.Listening;

/** The real loopback listener, driven over real sockets the way a browser would. */
public class LoopbackCallbackServerTest {
    private static final String STATE = "state-made-by-this-attempt";
    private Listening listening;

    @After
    public void stop() {
        if (listening != null) listening.close();
    }

    private Listening start() {
        listening = new LoopbackCallbackServer(List.of(0)).start(STATE);
        return listening;
    }

    /** Sends raw bytes and returns everything the listener answers. */
    private static String send(URI redirect, String request) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", redirect.getPort()), 3000);
            socket.setSoTimeout(8000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String get(URI redirect, String target) throws IOException {
        return send(redirect, "GET " + target + " HTTP/1.1\r\nHost: 127.0.0.1\r\nUser-Agent: test\r\n\r\n");
    }

    @Test
    public void theRedirectForThisAttemptCompletesItAndStopsListening() throws Exception {
        Listening session = start();
        assertEquals("127.0.0.1", session.redirectUri().getHost());
        assertEquals("/callback", session.redirectUri().getPath());
        assertEquals("http", session.redirectUri().getScheme());

        String response = get(session.redirectUri(), "/callback?code=abc%2F123&state=" + STATE);
        assertTrue(response, response.startsWith("HTTP/1.1 200 OK"));
        assertTrue(response.contains("You are signed in"));
        assertTrue(response.contains("Cache-Control: no-store"));
        assertEquals(Map.of("code", "abc/123", "state", STATE), session.callback().get(5, TimeUnit.SECONDS));

        assertThrows("One redirect is all it ever accepts", IOException.class, () -> {
            for (int i = 0; i < 20; i++) {
                get(session.redirectUri(), "/callback?code=again&state=" + STATE);
                Thread.sleep(50);
            }
        });
    }

    @Test
    public void aRefusalFromTheProviderIsDeliveredToo() throws Exception {
        Listening session = start();
        String response = get(session.redirectUri(), "/callback?error=access_denied&error_description=no&state=" + STATE);
        assertTrue(response.contains("Sign-in was not completed"));
        assertEquals("access_denied", session.callback().get(5, TimeUnit.SECONDS).get("error"));
    }

    @Test
    public void requestsWithoutThisAttemptsStateAreIgnoredAndListeningContinues() throws Exception {
        Listening session = start();
        for (String target : List.of(
                "/callback?code=injected&state=guessed-state",
                "/callback?code=injected",
                "/callback?state=" + STATE,
                "/callback",
                "/",
                "/favicon.ico",
                "/other?code=abc&state=" + STATE,
                "/callback/?code=abc&state=" + STATE,
                "/callback?code=abc&state=" + STATE + "x",
                "/callback?code=%ZZ&state=" + STATE)) {
            String response = get(session.redirectUri(), target);
            assertTrue(target + " -> " + response, response.startsWith("HTTP/1.1 404"));
            assertFalse(target, session.callback().isDone());
        }
        get(session.redirectUri(), "/callback?code=real&state=" + STATE);
        assertEquals("real", session.callback().get(5, TimeUnit.SECONDS).get("code"));
    }

    @Test
    public void onlyAPlainGetIsAccepted() throws Exception {
        Listening session = start();
        String target = "/callback?code=abc&state=" + STATE;
        for (String request : List.of(
                "POST " + target + " HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\n\r\n",
                "OPTIONS " + target + " HTTP/1.1\r\nHost: x\r\n\r\n",
                "GET " + target + "\r\n\r\n",
                "garbage\r\n\r\n",
                "\r\n\r\n")) {
            assertTrue(request, send(session.redirectUri(), request).startsWith("HTTP/1.1 404"));
        }
        assertFalse(session.callback().isDone());
    }

    @Test
    public void aDuplicatedParameterCannotReplaceTheFirst() throws Exception {
        Listening session = start();
        get(session.redirectUri(), "/callback?code=first&state=" + STATE + "&code=second&state=other");
        assertEquals(Map.of("code", "first", "state", STATE), session.callback().get(5, TimeUnit.SECONDS));
    }

    @Test
    public void nothingFromTheRequestIsEchoedIntoThePage() throws Exception {
        Listening session = start();
        String script = "%3Cscript%3Ealert(1)%3C%2Fscript%3E";
        String refused = get(session.redirectUri(), "/callback?code=" + script + "&state=wrong" + script);
        String accepted = get(session.redirectUri(), "/callback?code=" + script + "&error_description=" + script + "&state=" + STATE);
        for (String response : List.of(refused, accepted)) {
            assertFalse(response.contains("script"));
            assertFalse(response.contains("alert"));
            assertTrue(response.contains("X-Content-Type-Options: nosniff"));
        }
    }

    @Test
    public void aSilentOrOversizedConnectionDoesNotStopTheRealRedirect() throws Exception {
        Listening session = start();
        try (Socket idle = new Socket("127.0.0.1", session.redirectUri().getPort())) {
            String flood = "GET /callback?code=" + "a".repeat(20_000) + "&state=" + STATE + " HTTP/1.1\r\n\r\n";
            try {
                send(session.redirectUri(), flood);
            } catch (IOException tolerated) {
                // The listener may hang up on a request this large; it must simply survive it.
            }
            assertFalse(session.callback().isDone());
            get(session.redirectUri(), "/callback?code=real&state=" + STATE);
            assertEquals("real", session.callback().get(5, TimeUnit.SECONDS).get("code"));
            assertTrue(idle.isConnected());
        }
    }

    @Test
    public void closingReleasesThePortAndCancelsTheWait() throws Exception {
        Listening session = start();
        int port = session.redirectUri().getPort();
        session.close();
        session.close();
        assertTrue(session.callback().isCancelled());
        assertThrows(ConnectException.class, () -> new Socket("127.0.0.1", port).close());
        try (ServerSocket reuse = new ServerSocket()) {
            reuse.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), port));
        }
    }

    @Test
    public void aBusyPortFallsBackToTheNextAndAllBusyIsExplained() throws Exception {
        InetAddress loopback = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        try (ServerSocket first = new ServerSocket(0, 1, loopback); ServerSocket second = new ServerSocket(0, 1, loopback)) {
            int free;
            try (ServerSocket probe = new ServerSocket(0, 1, loopback)) {
                free = probe.getLocalPort();
            }
            listening = new LoopbackCallbackServer(List.of(first.getLocalPort(), second.getLocalPort(), free)).start(STATE);
            assertEquals(free, listening.redirectUri().getPort());

            AuthException failure = assertThrows(AuthException.class,
                    () -> new LoopbackCallbackServer(List.of(first.getLocalPort(), second.getLocalPort())).start(STATE));
            assertEquals(AuthException.Kind.NOT_AVAILABLE, failure.kind());
            assertTrue(failure.getMessage().contains(String.valueOf(first.getLocalPort())));
        }
    }

    @Test
    public void theDefaultPortsAreTheOnesTheDocumentationRegisters() {
        assertEquals(List.of(47821, 47822, 47823), LoopbackCallbackServer.DEFAULT_PORTS);
        assertThrows(IllegalArgumentException.class, () -> new LoopbackCallbackServer(List.of(0)).start(" "));
    }
}
