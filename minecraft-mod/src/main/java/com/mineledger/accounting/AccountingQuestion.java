package com.mineledger.accounting;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record AccountingQuestion(String id, String prompt, List<Account> debitAccounts,
        List<Account> creditAccounts, Account correctDebit, Account correctCredit,
        BigDecimal correctAmount, String explanation) {
    public AccountingQuestion {
        Objects.requireNonNull(id);
        Objects.requireNonNull(prompt);
        Objects.requireNonNull(explanation);
        Objects.requireNonNull(correctAmount);
        debitAccounts = List.copyOf(debitAccounts);
        creditAccounts = List.copyOf(creditAccounts);
        if (id.isBlank() || prompt.isBlank() || explanation.isBlank()
                || !debitAccounts.contains(correctDebit) || !creditAccounts.contains(correctCredit)
                || correctDebit == correctCredit || correctAmount.signum() <= 0) {
            throw new IllegalArgumentException("Invalid single-debit, single-credit question");
        }
    }
}
