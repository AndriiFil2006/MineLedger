package com.mineledger.accounting;

/** Raw amount input is parsed and validated by the grader, never by the screen. */
public record AccountingAnswer(Account debit, Account credit, String amountInput) {
    public static final int MAX_AMOUNT_INPUT_LENGTH = 32;
}
