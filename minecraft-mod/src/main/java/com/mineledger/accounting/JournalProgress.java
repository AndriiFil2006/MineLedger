package com.mineledger.accounting;

import java.util.HashSet;
import java.util.Set;

/** In-memory learning XP, separate from Minecraft experience and any world/player data. */
public final class JournalProgress {
    private final AccountingGrader grader = new AccountingGrader();
    private final Set<String> correctQuestionIds;
    private final Runnable onChange;

    public JournalProgress() { this(Set.of(), () -> { }); }

    public JournalProgress(Set<String> restored, Runnable onChange) {
        correctQuestionIds = new HashSet<>(restored);
        this.onChange = onChange;
    }

    public Set<String> correctQuestionIds() { return Set.copyOf(correctQuestionIds); }

    public AccountingResult submit(AccountingQuestion question, AccountingAnswer answer) {
        AccountingResult result = grader.grade(question, answer);
        if (result.correct() && correctQuestionIds.add(question.id())) onChange.run();
        return result;
    }

    public int correctCount() {
        return correctQuestionIds.size();
    }

    public int xp() {
        return correctCount() * 10;
    }
}
