package com.mineledger.accounting;

import static org.junit.Assert.*;
import java.math.BigDecimal;
import java.util.List;
import org.junit.Test;
import com.mineledger.accounting.AccountingResult.Issue;

public class AccountingGraderTest {
    private final AccountingGrader grader = new AccountingGrader();
    private final AccountingQuestion question = IntroJournalAssignment.QUESTIONS.getFirst();

    private AccountingResult grade(Account debit, Account credit, String amount) {
        return grader.grade(question, new AccountingAnswer(debit, credit, amount));
    }

    @Test
    public void allThreeManuallyVerifiedAnswersAreCorrect() {
        List<AccountingAnswer> answers = List.of(
                new AccountingAnswer(Account.INVENTORY, Account.ACCOUNTS_PAYABLE, "60"),
                new AccountingAnswer(Account.ADVERTISING_EXPENSE, Account.CASH, "25"),
                new AccountingAnswer(Account.CASH, Account.SERVICE_REVENUE, "80"));
        assertEquals(3, IntroJournalAssignment.QUESTIONS.size());
        for (int i = 0; i < answers.size(); i++) {
            AccountingResult result = grader.grade(IntroJournalAssignment.QUESTIONS.get(i), answers.get(i));
            assertTrue(result.correct());
            assertTrue(result.valid());
            assertTrue(result.issues().isEmpty());
            assertFalse(result.explanation().isBlank());
        }
    }

    @Test
    public void incorrectDebit() {
        assertEquals(List.of(Issue.INCORRECT_DEBIT), grade(Account.CASH, Account.ACCOUNTS_PAYABLE, "60").issues());
    }

    @Test
    public void incorrectCredit() {
        assertEquals(List.of(Issue.INCORRECT_CREDIT), grade(Account.INVENTORY, Account.CASH, "60").issues());
    }

    @Test
    public void incorrectAmount() {
        assertEquals(List.of(Issue.INCORRECT_AMOUNT), grade(Account.INVENTORY, Account.ACCOUNTS_PAYABLE, "59").issues());
    }

    @Test
    public void nonpositiveAmountsAreInvalid() {
        for (String amount : List.of("0", "0.00", "-1", "-0.01")) {
            AccountingResult result = grade(Account.INVENTORY, Account.ACCOUNTS_PAYABLE, amount);
            assertEquals(List.of(Issue.NONPOSITIVE_AMOUNT), result.issues());
            assertFalse(result.valid());
            assertFalse(result.correct());
        }
    }

    @Test
    public void sameAccountIsInvalid() {
        AccountingResult result = grade(Account.INVENTORY, Account.INVENTORY, "60");
        assertEquals(List.of(Issue.SAME_ACCOUNT), result.issues());
        assertFalse(result.valid());
    }

    @Test
    public void malformedOrMissingInputIsInvalid() {
        for (String amount : List.of("", " ", "abc", "NaN", "Infinity", "1e2", "60,00", "60.001", "9".repeat(33))) {
            assertEquals(List.of(Issue.INVALID_INPUT), grade(Account.INVENTORY, Account.ACCOUNTS_PAYABLE, amount).issues());
        }
        assertFalse(grade(null, Account.CASH, "60").valid());
        assertFalse(grade(Account.INVENTORY, null, "60").valid());
        assertFalse(grade(Account.INVENTORY, Account.CASH, null).valid());
        assertFalse(grader.grade(question, null).valid());
    }

    @Test
    public void accountNotOfferedIsInvalid() {
        AccountingQuestion restricted = new AccountingQuestion("restricted", "Test", List.of(Account.INVENTORY),
                List.of(Account.ACCOUNTS_PAYABLE), Account.INVENTORY, Account.ACCOUNTS_PAYABLE,
                BigDecimal.TEN, "Explanation");
        assertFalse(grader.grade(restricted, new AccountingAnswer(Account.CASH, Account.ACCOUNTS_PAYABLE, "10")).valid());
    }

    @Test
    public void decimalScaleAndOuterWhitespaceDoNotChangeAmount() {
        assertTrue(grade(Account.INVENTORY, Account.ACCOUNTS_PAYABLE, " 60.00 ").correct());
    }

    @Test
    public void allExpectedComponentsMustMatch() {
        AccountingResult result = grade(Account.CASH, Account.SERVICE_REVENUE, "25");
        assertFalse(result.correct());
        assertTrue(result.valid());
        assertEquals(List.of(Issue.INCORRECT_DEBIT, Issue.INCORRECT_CREDIT, Issue.INCORRECT_AMOUNT), result.issues());
    }

    @Test
    public void repeatedSubmissionsNeverDuplicateXp() {
        JournalProgress progress = new JournalProgress();
        AccountingAnswer correct = new AccountingAnswer(Account.INVENTORY, Account.ACCOUNTS_PAYABLE, "60");
        progress.submit(question, new AccountingAnswer(Account.CASH, Account.ACCOUNTS_PAYABLE, "60"));
        assertEquals(0, progress.xp());
        assertEquals(0, progress.correctCount());
        for (int i = 0; i < 5; i++) assertTrue(progress.submit(question, correct).correct());
        assertEquals(10, progress.xp());
        assertEquals(1, progress.correctCount());
        // Identity is the stable question ID, not the record instance.
        AccountingQuestion copy = new AccountingQuestion(question.id(), question.prompt(), question.debitAccounts(),
                question.creditAccounts(), question.correctDebit(), question.correctCredit(), question.correctAmount(), question.explanation());
        progress.submit(copy, correct);
        assertEquals(10, progress.xp());
        progress.submit(IntroJournalAssignment.QUESTIONS.get(1),
                new AccountingAnswer(Account.ADVERTISING_EXPENSE, Account.CASH, "25"));
        progress.submit(IntroJournalAssignment.QUESTIONS.get(2),
                new AccountingAnswer(Account.CASH, Account.SERVICE_REVENUE, "80"));
        assertEquals(30, progress.xp());
        assertEquals(3, progress.correctCount());
        assertEquals(0, new JournalProgress().xp());
    }
}
