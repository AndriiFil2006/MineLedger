package com.mineledger.accounting;

import java.math.BigDecimal;
import java.util.List;

/** Fixed, manually authored examples. Each amount applies to both journal lines. */
public final class IntroJournalAssignment {
    private static final List<Account> ACCOUNTS = List.of(Account.values());
    public static final List<AccountingQuestion> QUESTIONS = List.of(
            new AccountingQuestion("journal.inventory_on_credit",
                    "Steve purchases 4 diamond shovels at 15 emeralds each on credit to resell in his shop.",
                    ACCOUNTS, ACCOUNTS, Account.INVENTORY, Account.ACCOUNTS_PAYABLE, new BigDecimal("60"),
                    "The shovels are inventory held for resale. Debit Inventory for 4 x 15 = 60 emeralds "
                    + "to increase this asset. Credit Accounts Payable 60 because Steve owes the supplier; no cash was paid."),
            new AccountingQuestion("journal.cash_advertising",
                    "Alex pays 25 emeralds in cash for advertising her village bakery.",
                    ACCOUNTS, ACCOUNTS, Account.ADVERTISING_EXPENSE, Account.CASH, new BigDecimal("25"),
                    "Debit Advertising Expense 25 emeralds to recognize the cost of advertising. "
                    + "Credit Cash 25 because paying cash decreases an asset."),
            new AccountingQuestion("journal.cash_services",
                    "A village shop sells services to villagers for 80 emeralds cash.",
                    ACCOUNTS, ACCOUNTS, Account.CASH, Account.SERVICE_REVENUE, new BigDecimal("80"),
                    "Debit Cash 80 emeralds because the shop receives cash, increasing an asset. "
                    + "Credit Service Revenue 80 to recognize revenue earned by providing services."));

    private IntroJournalAssignment() {
    }
}
