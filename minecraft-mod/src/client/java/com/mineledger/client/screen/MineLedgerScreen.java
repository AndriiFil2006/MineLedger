package com.mineledger.client.screen;

import java.util.EnumMap;
import java.util.stream.Collectors;
import com.mineledger.client.BackendRuntime;
import com.mineledger.client.JournalSession;
import com.mineledger.backend.BackendConfig.Mode;
import com.mineledger.backend.ConnectionManager;
import com.mineledger.backend.ConnectionManager.Content;
import com.mineledger.backend.ConnectionManager.OfflineReason;
import com.mineledger.backend.ConnectionManager.State;
import com.mineledger.backend.RemoteCurriculumBrowser;
import net.minecraft.client.Minecraft;
import com.mineledger.accounting.mastery.LearningSession;
import com.mineledger.accounting.IntroJournalAssignment;
import com.mineledger.education.Accounting101;
import com.mineledger.education.CourseProgress;
import com.mineledger.education.AssignmentDirectory;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.network.chat.Component;

public final class MineLedgerScreen extends ScaledAppScreen {
    private enum Section {
        COURSES("courses"), ASSIGNMENTS("assignments"), PROGRESS("progress"), SETTINGS("settings");

        private final String key;

        Section(String key) {
            this.key = "screen.mineledger." + key;
        }

        Component label() {
            return Component.translatable(key);
        }
    }

    private final MineOsDesktopScreen desktop;
    private final CourseBrowser courses = new CourseBrowser(Accounting101.CATALOG);
    private final AssignmentDirectory assignments = new AssignmentDirectory(Accounting101.CATALOG);
    private ScrollableLayout assignmentList;
    private double assignmentScroll;
    private final EnumMap<Section, Button> navigation = new EnumMap<>(Section.class);
    private Section selected = Section.COURSES;
    private final Minecraft client = Minecraft.getInstance();
    private final ConnectionManager connection = BackendRuntime.connection();
    /** Exists only while backend content is shown; local practice never goes through it. */
    private RemoteCurriculumBrowser remote;
    private boolean building;
    /** A page load slower than this shows "Loading..."; a faster one replaces the current page directly. */
    private static final long LOADING_NOTICE_DELAY_MILLIS = 300;
    private long remoteLoadingSince = -1;
    private boolean loadingNoticeShown;
    private int left;
    private int top;
    private int panelWidth;
    private int panelHeight;

    public MineLedgerScreen(MineOsDesktopScreen desktop) {
        super(Component.translatable("screen.mineledger.title"));
        this.desktop = desktop;
        connection.appOpened();
    }

    /** Connection and backend results arrive on the client thread; only the screen currently shown is rebuilt. */
    private void refresh() {
        if (!building && client.gui.screen() == this) rebuildWidgets();
    }

    private void connectionChanged() {
        // After a successful reconnect, finish the load that failed when the connection dropped.
        if (connection.state() == State.ONLINE && !connection.checking() && remote != null && remote.canRetry()) remote.retry();
        refresh();
    }

    private void syncRemote() {
        if (connection.content() == Content.REMOTE) {
            if (remote == null) {
                remote = new RemoteCurriculumBrowser(connection.api(), client::execute);
                remote.setListener(this::refresh);
                remote.setFailureListener(connection::reportRequestFailure);
            }
            remote.open();
        } else if (remote != null) {
            remote.close();
            remote = null;
        }
    }

    private final Runnable connectionListener = this::connectionChanged;

    @Override
    public void removed() {
        connection.removeListener(connectionListener);
        if (remote != null) remote.close(); // Late responses must not touch a screen that is gone.
        super.removed();
    }

    @Override
    protected void init() {
        building = true;
        try {
            connection.addListener(connectionListener);
            syncRemote();
            build();
        } finally {
            building = false;
        }
    }

    /** There is nothing to keep on screen before the first page has loaded, so that load shows at once. */
    private boolean loadingNoticeDue() {
        return remote != null && remote.loading() && (!remote.canGoBack()
                || System.currentTimeMillis() - remoteLoadingSince >= LOADING_NOTICE_DELAY_MILLIS);
    }

    @Override
    public void tick() {
        super.tick();
        if (!loadingNoticeShown && loadingNoticeDue()) rebuildWidgets();
    }

    private void build() {
        if (remote == null || !remote.loading()) remoteLoadingSince = -1;
        else if (remoteLoadingSince < 0) remoteLoadingSince = System.currentTimeMillis();
        loadingNoticeShown = loadingNoticeDue();
        if (assignmentList != null) assignmentScroll = assignmentList.getScrollAmount();
        assignmentList = null;
        panelWidth = 600;
        left = 0;
        top = 0;
        navigation.clear();
        int tabs = Section.values().length;
        int tabWidth = (panelWidth - 32 - 6 * (tabs - 1)) / tabs;
        for (Section section : Section.values()) {
            Button tab = addRenderableWidget(Button.builder(section.label(), button -> select(section))
                    .bounds(left + 16 + section.ordinal() * (tabWidth + 6), top + 40, tabWidth, 20).build());
            navigation.put(section, tab);
        }
        navigation.forEach((key, button) -> button.active = key != selected);
        var session = JournalSession.mastery();
        int width = panelWidth - 76;
        Content content = connection.content();
        boolean localList = selected == Section.ASSIGNMENTS && content == Content.LOCAL;
        LinearLayout body = LinearLayout.vertical().spacing(10);
        if (selected == Section.SETTINGS) {
            settings(body, width);
        } else if (selected == Section.PROGRESS) {
            body.addChild(new MultiLineTextWidget(progressMessage(session).copy()
                    .withStyle(style -> style.withColor(0x35383D).withoutShadow()), font).setMaxWidth(width));
        } else if (content == Content.NONE) {
            waitingForConnection(body, width);
        } else if (content == Content.REMOTE && selected == Section.COURSES) {
            boolean lost = connection.state() == State.CONNECTION_LOST;
            if (lost) {
                AssignmentRows.text(body, width, font, "Connection lost. " + problem()
                        + "\nWhat you already opened is still shown. Nothing was submitted.");
                connectionActions(body);
            }
            RemoteCourseBrowser.populate(body, width, font, remote, this::rebuildWidgets, lost, loadingNoticeShown);
        } else if (content == Content.REMOTE) {
            AssignmentRows.text(body, width, font, "You are online. Open the Courses tab to browse assignments and "
                    + "preview their questions. Online assignments are view-only for now: they are not graded yet.\n\n"
                    + "To answer questions, switch to offline practice. Offline practice earns local XP on this "
                    + "computer only. It is not official coursework and is never sent to the server.");
            connectionActions(body);
        } else if (selected == Section.COURSES) {
            offlineNotice(body, width);
            courses.populate(body, width, font, JournalSession.mastery(), this::rebuildWidgets,
                    (service, recharge) -> minecraft.gui.setScreen(new ConceptAssignmentScreen(this, service, recharge)));
        } else {
            offlineNotice(body, width);
            String previousCourse = null;
            for (var group : assignments.groups(session)) {
                if (!group.course().id().equals(previousCourse)) {
                    AssignmentRows.text(body, width, font, group.course().title());
                    previousCourse = group.course().id();
                }
                AssignmentRows.text(body, width, font, "Unit " + group.unit().number() + ": " + group.unit().title());
                AssignmentRows.append(body, width, font, group.entries(), id -> {
                    assignments.select(id, session).ifPresent(item -> {
                        if (assignmentList != null) assignmentScroll = assignmentList.getScrollAmount();
                        minecraft.gui.setScreen(new ConceptAssignmentScreen(this, item.service(), item.recharge()));
                    });
                });
            }
            if (Accounting101.CATALOG.courses().isEmpty()) AssignmentRows.text(body, width, font, "No assignments available yet.");
        }
        body.arrangeElements();
        panelHeight = localList ? Math.max(220, Math.min(360, body.getHeight() + 153))
                : Math.max(220, body.getHeight() + 153);
        fitWindow(panelWidth, panelHeight);
        if (localList && body.getHeight() > panelHeight - 153) {
            assignmentList = new ScrollableLayout(minecraft, body, panelHeight - 153, ScrollableLayout.ReserveStrategy.RIGHT);
            assignmentList.arrangeElements();
            assignmentList.setX(left + 28);
            assignmentList.setY(top + 103);
            assignmentList.visitWidgets(widget -> {
                addRenderableWidget(widget);
                if (widget instanceof AbstractScrollArea area) area.setScrollAmount(assignmentScroll);
            });
        } else {
            body.setX(left + 28);
            body.setY(top + 103);
            body.visitWidgets(this::addRenderableWidget);
        }
        if (localList) {
            addRenderableWidget(Button.builder(Component.literal("Practice / Lab: Journal Entries"),
                    button -> minecraft.gui.setScreen(new JournalEntryScreen(this)))
                    .bounds(left + panelWidth - 252, top + panelHeight - 32, 236, 20).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("screen.mineledger.desktop"),
                button -> minecraft.gui.setScreen(desktop))
                .bounds(left + 16, top + panelHeight - 32, Math.min(160, panelWidth - 32), 20).build());
    }

    private String problem() {
        return connection.problem().map(Throwable::getMessage).orElse("");
    }

    private String status() {
        if (connection.checking()) return "Checking connection...";
        return switch (connection.state()) {
            case CHECKING -> "Checking connection...";
            case ONLINE -> "Online";
            case CONNECTION_LOST -> "Connection lost";
            case OFFLINE_PRACTICE -> "Offline practice";
            case UNAVAILABLE -> "Server unavailable";
        };
    }

    /** Retry Connection and Switch to Offline Practice, each only when it would do something. */
    private void connectionActions(LinearLayout body) {
        LinearLayout row = body.addChild(LinearLayout.horizontal().spacing(6));
        if (connection.preference() != Mode.OFFLINE) {
            Button retry = row.addChild(Button.builder(Component.literal(connection.checking() ? "Checking..." : "Retry Connection"),
                    button -> connection.retry()).size(150, 20).build());
            retry.active = connection.canRetry();
        }
        if (connection.canSwitchToOfflinePractice()) {
            row.addChild(Button.builder(Component.literal("Switch to Offline Practice"),
                    button -> connection.switchToOfflinePractice()).size(180, 20).build());
        }
    }

    private void waitingForConnection(LinearLayout body, int width) {
        if (connection.state() == State.UNAVAILABLE) {
            AssignmentRows.text(body, width, font, "The MineLedger server cannot be used right now.\n" + problem()
                    + "\n\nYour connection setting is Online Required, so offline content is not shown automatically. "
                    + "You can try again, or switch to offline practice yourself.");
        } else {
            AssignmentRows.text(body, width, font, "Checking the MineLedger server...");
        }
        connectionActions(body);
    }

    /** One compact line (plus a reconnect button when it applies) above local practice content. */
    private void offlineNotice(LinearLayout body, int width) {
        OfflineReason reason = connection.offlineReason().orElse(OfflineReason.CHOSEN);
        String why = switch (reason) {
            case CHOSEN -> "Offline practice.";
            case FALLBACK -> "The MineLedger server is not available, so this is offline practice.";
            case SWITCHED -> "You switched to offline practice.";
        };
        AssignmentRows.text(body, width, font, why + " Progress and XP here are local: saved on this computer only, "
                + "not official coursework.");
        if (connection.preference() != Mode.OFFLINE) connectionActions(body);
    }

    private void settings(LinearLayout body, int width) {
        AssignmentRows.text(body, width, font, "Connection: " + status()
                + (connection.state() == State.ONLINE || problem().isEmpty() ? "" : "\n" + problem()));
        AssignmentRows.text(body, width, font, "How should MineLedger connect?");
        LinearLayout choices = body.addChild(LinearLayout.horizontal().spacing(6));
        choice(choices, Mode.AUTO, "Automatic");
        choice(choices, Mode.BACKEND, "Online Required");
        choice(choices, Mode.OFFLINE, "Offline Practice");
        AssignmentRows.text(body, width, font, switch (connection.preference()) {
            case AUTO -> "Automatic (selected): uses the MineLedger server when it is available. If it is not, you get "
                    + "offline practice instead.";
            case BACKEND -> "Online Required (selected): always uses the MineLedger server. If it cannot be reached you "
                    + "see an error, and offline content is only used if you choose it.";
            case OFFLINE -> "Offline Practice (selected): never contacts the server. Uses the lessons and questions "
                    + "included with the game.";
        });
        connectionActions(body);
        AssignmentRows.text(body, width, font, "Offline practice saves progress and local XP on this computer only. "
                + "It is practice, not official coursework, and it is never uploaded.");
        connection.saveProblem().ifPresent(message -> AssignmentRows.text(body, width, font, message));
        BackendRuntime.configProblem().ifPresent(message -> AssignmentRows.text(body, width, font,
                "The settings file could not be read, so default settings are in use. Choosing an option above "
                + "replaces it (a copy of the old file is kept)."));
    }

    private void choice(LinearLayout row, Mode mode, String label) {
        Button button = row.addChild(Button.builder(Component.literal(label), pressed -> connection.setPreference(mode))
                .size(170, 20).build());
        button.active = connection.preference() != mode;
    }

    private Component progressMessage(LearningSession session) {
        CourseProgress totals = new CourseProgress(session);
        StringBuilder text = new StringBuilder("Offline practice progress (this computer only)\n"
                + "Local XP is practice XP. It is not official coursework and is never sent to the server.\n\n");
        for (var course : Accounting101.CATALOG.courses()) {
            text.append(course.title()).append('\n').append(totals.course(course).assignmentsLabel()).append("\n\n");
        }
        for (var assignment : Accounting101.CATALOG.availableAssignments()) {
            var service = session.open(assignment);
            text.append(assignment.title()).append('\n').append(service.completedConcepts()).append(" / ")
                    .append(assignment.concepts().size()).append(" concepts completed; ").append(service.xp()).append(" / ")
                    .append(assignment.concepts().size() * 20).append(" local XP\n")
                    .append(assignment.concepts().stream().map(concept -> concept.title() + ": "
                            + service.progress().concept(concept.id()).credits() + "/" + concept.requiredCorrectAnswers())
                            .collect(Collectors.joining("\n"))).append("\n\n");
        }
        text.append("Practice / Lab: Journal Entries\n").append(JournalSession.progress().correctCount()).append(" / ")
                .append(IntroJournalAssignment.QUESTIONS.size()).append(" questions correct; ").append(JournalSession.progress().xp())
                .append(" local XP. Each lab question awards 10 local XP only once.\n").append(JournalSession.storageStatus());
        return Component.literal(text.toString());
    }

    private void select(Section section) {
        selected = section;
        rebuildWidgets();
    }

    @Override
    protected void extractWindowBackground(GuiGraphicsExtractor graphics) {
        graphics.fill(left + 3, top + 3, left + panelWidth + 3, top + panelHeight + 3, 0xFFD5D6DA);
        graphics.fill(left, top, left + panelWidth, top + panelHeight, 0xFFFFFFFF);
        graphics.outline(left, top, panelWidth, panelHeight, 0xFFCFD1D5);
        graphics.fill(left + 1, top + 1, left + panelWidth - 1, top + 30, 0xFFB6202D);
        graphics.fill(left + 16, top + 11, left + 20, top + 20, 0xFFFFFFFF);
        graphics.text(font, title, left + 28, top + 11, 0xFFFFFFFF, false);
        String status = status();
        graphics.text(font, status, left + panelWidth - 16 - font.width(status), top + 11, 0xFFFFFFFF, false);
        graphics.fill(left + 16, top + 72, left + panelWidth - 16, top + panelHeight - 44, 0xFFF7F7F8);
        graphics.text(font, selected.label(), left + 28, top + 84, 0xFFB6202D, false);
    }

    @Override
    public void onClose() {
        boolean wentBack = selected == Section.COURSES && switch (connection.content()) {
            case REMOTE -> remote != null && remote.back();
            case LOCAL -> courses.back();
            case NONE -> false;
        };
        if (wentBack) rebuildWidgets();
        else minecraft.gui.setScreen(desktop);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
