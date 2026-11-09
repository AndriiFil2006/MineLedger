package com.mineledger.accounting;

import java.util.HashSet;
import java.util.Set;

/** In-memory learning XP, separate from Minecraft experience and any world/player data. */
public final class JournalProgress {
    private final AccountingGrader grader = new AccountingGrader();
    private final Set<String> correctQuestionIds = new HashSet<>();

    public AccountingResult submit(AccountingQuestion question, AccountingAnswer answer) {
        AccountingResult result = grader.grade(question, answer);
        if (result.correct()) correctQuestionIds.add(question.id());
        return result;
    }

    public int correctCount() {
        return correctQuestionIds.size();
    }

    public int xp() {
        return correctCount() * 10;
    }
}
