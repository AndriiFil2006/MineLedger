package com.mineledger.accounting;

import java.util.List;

public record AccountingResult(boolean correct, List<Issue> issues, String explanation) {
    public enum Issue {
        INVALID_INPUT, NONPOSITIVE_AMOUNT, SAME_ACCOUNT,
        INCORRECT_DEBIT, INCORRECT_CREDIT, INCORRECT_AMOUNT
    }

    public AccountingResult {
        issues = List.copyOf(issues);
    }

    public boolean valid() {
        return !issues.contains(Issue.INVALID_INPUT) && !issues.contains(Issue.NONPOSITIVE_AMOUNT)
                && !issues.contains(Issue.SAME_ACCOUNT);
    }
}
