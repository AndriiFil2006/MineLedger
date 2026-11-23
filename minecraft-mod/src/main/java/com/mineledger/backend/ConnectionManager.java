package com.mineledger.backend;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import com.mineledger.backend.BackendConfig.Mode;
import com.mineledger.backend.RemoteCurriculum.CourseSummary;
import com.mineledger.backend.RemoteCurriculum.Meta;
import com.mineledger.curriculum.CurriculumLoader;

/**
 * The single owner of "are we online?" for the running game. It turns the saved preference
 * (automatic, online required, offline practice) plus the result of asynchronous checks into the state the
 * screens display and the content they are allowed to show.
 *
 * <p>It decides only where browsable curriculum comes from. It never grades, never reads or writes
 * progress, and never sends anything to the backend except read-only checks, so local practice results
 * cannot become backend records through it.
 *
 * <p>Threading: call every method on the owner thread (Minecraft's client thread). Checks run on the API's
 * background threads and their results are handed to {@code ownerThread} before any state changes. Each
 * check carries a ticket; a result whose ticket is no longer current is discarded.
 */
public final class ConnectionManager {
    public enum State {
        /** A check is running and nothing has been decided yet, or a recheck is refreshing the current state. */
        CHECKING,
        /** The backend passed the last check; backend curriculum is shown (read-only). */
        ONLINE,
        /** A request failed while online. Loaded backend content stays on screen until the player decides. */
        CONNECTION_LOST,
        /** Bundled curriculum with local grading and local XP. See {@link OfflineReason}. */
        OFFLINE_PRACTICE,
        /** Online is required by the player's preference and the backend failed its check. */
        UNAVAILABLE
    }

    /** Which curriculum the screens may show right now. */
    public enum Content { NONE, LOCAL, REMOTE }

    public enum OfflineReason {
        /** The saved preference is Offline Practice; the backend is never contacted. */
        CHOSEN,
        /** Automatic mode could not use the backend. */
        FALLBACK,
        /** The player pressed Switch to Offline Practice; lasts until they reconnect or change the preference. */
        SWITCHED
    }

    /** Persists a changed preference; returns a message for the player if it could not be saved. */
    public interface PreferenceStore {
        Optional<String> save(Mode preference);
    }

    /** The API contract this client understands (see the backend's {@code API_VERSION}). */
    public static final int SUPPORTED_API_VERSION = 1;
    /** Opening the app again sooner than this reuses the last result instead of checking again. */
    public static final long RECHECK_AFTER_MILLIS = 15_000;

    private final Supplier<? extends CurriculumApi> apiFactory;
    private final Executor ownerThread;
    private final LongSupplier clockMillis;
    private final PreferenceStore store;
    private final Set<Runnable> listeners = new LinkedHashSet<>();
    private CurriculumApi api;
    private Mode preference;
    private State state;
    private Content content;
    private OfflineReason offlineReason;
    private BackendException problem;
    private String saveProblem;
    private long ticket;
    private boolean checking;
    private boolean checkedOnce;
    private long lastCheckMillis;

    /**
     * @param apiFactory called at most once, and only when a check is actually needed, so an offline
     *                   preference never creates an HTTP client
     */
    public ConnectionManager(Mode preference, Supplier<? extends CurriculumApi> apiFactory, Executor ownerThread,
            LongSupplier clockMillis, PreferenceStore store) {
        this.apiFactory = apiFactory;
        this.ownerThread = ownerThread;
        this.clockMillis = clockMillis;
        this.store = store;
        this.preference = preference;
        applyPreferenceWithoutChecking();
    }

    private void applyPreferenceWithoutChecking() {
        if (preference == Mode.OFFLINE) {
            state = State.OFFLINE_PRACTICE;
            content = Content.LOCAL;
            offlineReason = OfflineReason.CHOSEN;
        } else {
            state = State.CHECKING;
            content = Content.NONE;
            offlineReason = null;
        }
        problem = null;
    }

    public Mode preference() { return preference; }
    public State state() { return state; }
    public Content content() { return content; }
    /** True while a check is in flight (Retry is pointless until it finishes). */
    public boolean checking() { return checking; }
    public Optional<OfflineReason> offlineReason() { return Optional.ofNullable(offlineReason); }
    /** Why the backend is not in use, when a check or request failed. */
    public Optional<BackendException> problem() { return Optional.ofNullable(problem); }
    /** Set when the last preference change could not be written to disk (it still applies for this session). */
    public Optional<String> saveProblem() { return Optional.ofNullable(saveProblem); }
    public boolean canRetry() { return preference != Mode.OFFLINE && !checking; }
    public boolean canSwitchToOfflinePractice() { return content != Content.LOCAL; }

    /** The API for browsing backend content. Only meaningful while {@link #content()} is REMOTE. */
    public CurriculumApi api() {
        if (api == null) api = apiFactory.get();
        return api;
    }

    public void addListener(Runnable listener) { listeners.add(listener); }
    public void removeListener(Runnable listener) { listeners.remove(listener); }

    /**
     * The player opened MineLedger. Checks the backend unless the preference is offline, the player
     * switched to offline practice for this session, a check is already running, or one finished recently.
     */
    public void appOpened() {
        if (preference == Mode.OFFLINE || offlineReason == OfflineReason.SWITCHED || checking) return;
        if (checkedOnce && clockMillis.getAsLong() - lastCheckMillis < RECHECK_AFTER_MILLIS) return;
        // Whatever was on screen in an earlier visit is gone, so a lost connection is decided afresh.
        if (state == State.CONNECTION_LOST) {
            state = State.CHECKING;
            content = Content.NONE;
        }
        check(false);
    }

    /** Retry Connection / Reconnect. Ignored in offline preference and while a check is running. */
    public void retry() {
        if (!canRetry()) return;
        check(state == State.CONNECTION_LOST);
        changed();
    }

    /**
     * An explicit choice to practice with bundled content now, without changing the saved preference.
     * Abandons any check in flight; nothing switches back until the player reconnects.
     */
    public void switchToOfflinePractice() {
        if (!canSwitchToOfflinePractice()) return;
        ticket++;
        checking = false;
        state = State.OFFLINE_PRACTICE;
        content = Content.LOCAL;
        offlineReason = OfflineReason.SWITCHED;
        changed();
    }

    /** Settings: applies immediately and is saved. Choosing the current preference again does nothing. */
    public void setPreference(Mode chosen) {
        if (chosen == null || chosen == preference) return;
        saveProblem = store.save(chosen).orElse(null);
        preference = chosen;
        ticket++;
        checking = false;
        if (chosen == Mode.OFFLINE) {
            applyPreferenceWithoutChecking();
        } else {
            offlineReason = null;
            // "Online required" must not keep showing bundled content while it finds out.
            if (chosen == Mode.BACKEND && content == Content.LOCAL) content = Content.NONE;
            if (content != Content.REMOTE) state = State.CHECKING;
            check(false);
        }
        changed();
    }

    /**
     * A backend request made by a screen failed. While online, a failure that means the server is not
     * answering becomes CONNECTION_LOST; the content already loaded stays available.
     */
    public void reportRequestFailure(BackendException failure) {
        if (state != State.ONLINE || failure == null || failure.backendReachable()) return;
        state = State.CONNECTION_LOST;
        problem = failure;
        changed();
    }

    private void check(boolean keepRemoteContentOnFailure) {
        long mine = ++ticket;
        checking = true;
        if (content == Content.NONE) state = State.CHECKING;
        CompletableFuture<List<CourseSummary>> probe;
        try {
            probe = probe(api());
        } catch (RuntimeException exception) {
            probe = CompletableFuture.failedFuture(exception);
        }
        probe.whenCompleteAsync((courses, failure) -> {
            if (mine != ticket) return;
            checking = false;
            checkedOnce = true;
            lastCheckMillis = clockMillis.getAsLong();
            if (failure == null) {
                state = State.ONLINE;
                content = Content.REMOTE;
                offlineReason = null;
                problem = null;
            } else {
                problem = BackendException.from(failure);
                if (keepRemoteContentOnFailure) {
                    state = State.CONNECTION_LOST;
                } else if (preference == Mode.AUTO) {
                    state = State.OFFLINE_PRACTICE;
                    content = Content.LOCAL;
                    offlineReason = OfflineReason.FALLBACK;
                } else {
                    state = State.UNAVAILABLE;
                    content = Content.NONE;
                    offlineReason = null;
                }
            }
            changed();
        }, ownerThread);
    }

    /**
     * Usable means more than "answers HTTP 200": the server is healthy, speaks the API version this client
     * was built for, holds curriculum in a schema this client understands, and actually returns courses.
     */
    static CompletableFuture<List<CourseSummary>> probe(CurriculumApi api) {
        return api.health().thenCompose(health -> {
            if (!health.ok()) throw BackendException.incompatible("The MineLedger server reports a problem (" + health.status() + ").");
            return api.meta().exceptionally(failure -> {
                BackendException error = BackendException.from(failure);
                if (error.kind() == BackendException.Kind.HTTP_STATUS && error.status() == 404) {
                    throw BackendException.incompatible("The MineLedger server is an older version that this game cannot use.");
                }
                throw error;
            });
        }).thenCompose(meta -> {
            requireCompatible(meta);
            return api.courses().thenApply(courses -> {
                if (courses.isEmpty() || courses.size() != meta.courseCount()) {
                    throw BackendException.incompatible("The MineLedger server did not return its courses.");
                }
                return courses;
            });
        });
    }

    private static void requireCompatible(Meta meta) {
        if (meta.apiVersion() != SUPPORTED_API_VERSION) {
            throw BackendException.incompatible("The MineLedger server uses a different version (API " + meta.apiVersion()
                    + ") than this game supports (API " + SUPPORTED_API_VERSION + ").");
        }
        if (meta.courseCount() < 1 || meta.curriculumSchemaVersions().isEmpty()) {
            throw BackendException.incompatible("The MineLedger server has no courses yet.");
        }
        if (meta.curriculumSchemaVersions().stream().anyMatch(version -> version != CurriculumLoader.SCHEMA_VERSION)) {
            throw BackendException.incompatible("The MineLedger server's course format is not supported by this game.");
        }
    }

    private void changed() {
        for (Runnable listener : List.copyOf(listeners)) listener.run();
    }
}
