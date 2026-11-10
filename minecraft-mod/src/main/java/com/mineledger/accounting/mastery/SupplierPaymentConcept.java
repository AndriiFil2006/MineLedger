package com.mineledger.accounting.mastery;

import java.util.List;

/** Paying an existing liability: no new purchase or expense is recorded. */
public final class SupplierPaymentConcept {
    public static final String CONCEPT_ID = "payables.cash_payments";
    public static final AccountingConcept CONCEPT = new AccountingConcept(CONCEPT_ID, "Paying Suppliers",
            "Record cash payments against existing supplier debts. Master two distinct questions to earn 20 learning XP. "
            + "You can retry missed questions as often as needed.", List.of(
                    new MultipleChoiceQuestion("payables.shovels_settlement", CONCEPT_ID,
                            "Steve already recorded 60 emeralds owed for diamond shovels. He now pays the supplier "
                            + "all 60 emeralds in cash, with no discount. Which entry records this payment?",
                            List.of(
                                    option("settlement.inventory", "Debit Inventory 60; Credit Cash 60."),
                                    option("settlement.reversed", "Debit Cash 60; Credit Accounts Payable 60."),
                                    option("settlement.payable_cash", "Debit Accounts Payable 60; Credit Cash 60."),
                                    option("settlement.expense", "Debit Supplies Expense 60; Credit Cash 60.")),
                            "settlement.payable_cash",
                            "The inventory purchase was already recorded. Paying the debt decreases Accounts Payable "
                            + "with a 60-emerald debit and decreases Cash with a 60-emerald credit. Do not record inventory again."),
                    new MultipleChoiceQuestion("payables.books_partial", CONCEPT_ID,
                            "Alex's bookshop owes its supplier 80 emeralds for books already received and recorded. "
                            + "Alex pays 30 emeralds in cash now and will pay the remaining 50 later. Which entry records today's payment?",
                            List.of(
                                    option("partial.full_balance", "Debit Accounts Payable 80; Credit Cash 80."),
                                    option("partial.payable_cash", "Debit Accounts Payable 30; Credit Cash 30."),
                                    option("partial.remaining", "Debit Accounts Payable 50; Credit Cash 50."),
                                    option("partial.inventory", "Debit Inventory 30; Credit Cash 30.")),
                            "partial.payable_cash",
                            "Record only the 30 emeralds paid today: debit Accounts Payable 30 and credit Cash 30. "
                            + "The unpaid liability remains 80 - 30 = 50 emeralds. No new inventory or expense arises."),
                    new MultipleChoiceQuestion("payables.two_invoices", CONCEPT_ID,
                            "A village trading shop has two already-recorded supplier invoices, for 20 and 16 emeralds. "
                            + "It settles both with one cash payment, without discounts. Which combined payment entry is correct?",
                            List.of(
                                    option("invoices.revenue", "Debit Cash 36; Credit Service Revenue 36."),
                                    option("invoices.one_only", "Debit Accounts Payable 20; Credit Cash 20."),
                                    option("invoices.expense", "Debit Inventory Expense 36; Credit Cash 36."),
                                    option("invoices.payable_cash", "Debit Accounts Payable 36; Credit Cash 36.")),
                            "invoices.payable_cash",
                            "20 + 16 = 36 emeralds paid. Debit Accounts Payable 36 to remove the two recorded debts "
                            + "and credit Cash 36 for the payment. Settling supplier invoices does not create revenue or a new expense.")));

    private static AnswerOption option(String id, String text) { return new AnswerOption(id, text); }

    private SupplierPaymentConcept() { }
}
