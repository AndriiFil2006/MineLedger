package com.mineledger.accounting;

import java.util.List;
import com.mineledger.curriculum.CurriculumContent;

/** Compatibility handle for the standalone lab, separate from course mastery. */
public final class IntroJournalAssignment {
    public static final List<AccountingQuestion> QUESTIONS = CurriculumContent.get().labs().get("intro_journal_practice").questions();
    private IntroJournalAssignment() { }
}
