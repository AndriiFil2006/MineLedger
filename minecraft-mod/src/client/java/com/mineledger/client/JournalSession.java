package com.mineledger.client;

import com.mineledger.accounting.JournalProgress;
import com.mineledger.accounting.mastery.LearningSession;

/** One progress object for this client process. Nothing is serialized or sent to the server. */
public final class JournalSession {
    public static final JournalProgress PROGRESS = new JournalProgress();
    public static final LearningSession MASTERY = new LearningSession();

    private JournalSession() {
    }
}
