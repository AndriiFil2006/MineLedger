package com.mineledger.accounting;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import com.mineledger.accounting.AccountingResult.Issue;

public final class AccountingGrader {
    public AccountingResult grade(AccountingQuestion question, AccountingAnswer answer) {
        Objects.requireNonNull(question);
        if (answer == null || answer.debit() == null || answer.credit() == null
                || !question.debitAccounts().contains(answer.debit())
                || !question.creditAccounts().contains(answer.credit())
                || answer.amountInput() == null) {
            return invalid(Issue.INVALID_INPUT, "Choose both accounts and enter an emerald amount.");
        }
        String input = answer.amountInput().strip();
        // Plain decimal currency only: reject exponents, separators, NaN and excessive precision.
        if (input.length() > 32 || !input.matches("-?[0-9]+(?:\\.[0-9]{1,2})?")) {
            return invalid(Issue.INVALID_INPUT,
                    "Enter a number with at most two decimal places, such as 60 or 60.00.");
        }
        BigDecimal amount = new BigDecimal(input);
        if (amount.signum() <= 0) {
            return invalid(Issue.NONPOSITIVE_AMOUNT, "The emerald amount must be greater than zero.");
        }
        if (answer.debit() == answer.credit()) {
            return invalid(Issue.SAME_ACCOUNT, "Choose different debit and credit accounts for this transaction.");
        }
        List<Issue> issues = new ArrayList<>();
        if (answer.debit() != question.correctDebit()) issues.add(Issue.INCORRECT_DEBIT);
        if (answer.credit() != question.correctCredit()) issues.add(Issue.INCORRECT_CREDIT);
        if (amount.compareTo(question.correctAmount()) != 0) issues.add(Issue.INCORRECT_AMOUNT);
        String feedback = issues.isEmpty() ? "Correct! " : "Not quite. Check "
                + String.join(", ", issues.stream().map(issue -> switch (issue) {
                    case INCORRECT_DEBIT -> "the debit account";
                    case INCORRECT_CREDIT -> "the credit account";
                    default -> "the amount";
                }).toList()) + ". ";
        return new AccountingResult(issues.isEmpty(), issues, feedback + question.explanation());
    }

    private AccountingResult invalid(Issue issue, String feedback) {
        return new AccountingResult(false, List.of(issue), feedback);
    }
}
