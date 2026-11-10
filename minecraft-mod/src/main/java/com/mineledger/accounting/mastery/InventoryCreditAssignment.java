package com.mineledger.accounting.mastery;

import java.util.List;

/** Fixed, manually checked inventory transactions; no generated content or remote services. */
public final class InventoryCreditAssignment {
    public static final String CONCEPT_ID = "inventory.purchases_on_credit";
    public static final Assignment ASSIGNMENT = new Assignment("inventory_fundamentals", "Shop Accounting Foundations", List.of(
            new AccountingConcept(CONCEPT_ID, "Recording Inventory Purchases on Credit",
                    "Record goods bought for resale when payment is owed to a supplier. "
                    + "Answer two distinct questions correctly to master this concept and earn 20 learning XP.",
                    List.of(
                            new MultipleChoiceQuestion("inventory.shovels", CONCEPT_ID,
                                    "Steve purchases four diamond shovels at 15 emeralds each on credit for resale in his shop. "
                                    + "No cash is paid. Which entry records the purchase?",
                                    List.of(
                                            option("shovels.cash", "Debit Inventory 60; Credit Cash 60."),
                                            option("shovels.inventory_payable", "Debit Inventory 60; Credit Accounts Payable 60."),
                                            option("shovels.expense", "Debit Supplies Expense 60; Credit Accounts Payable 60."),
                                            option("shovels.reversed", "Debit Accounts Payable 60; Credit Inventory 60.")),
                                    "shovels.inventory_payable",
                                    "Four shovels x 15 = 60 emeralds. Goods held for resale increase Inventory (debit). "
                                    + "The unpaid supplier balance increases Accounts Payable (credit). Cash does not change."),
                            new MultipleChoiceQuestion("inventory.books_discount", CONCEPT_ID,
                                    "Alex's village bookshop receives 10 books for resale at 9 emeralds each. "
                                    + "The supplier grants an immediate 10-emerald trade discount on this invoice. "
                                    + "The entire net invoice is due next week. Which entry records receipt of the books?",
                                    List.of(
                                            option("books.gross", "Debit Inventory 90; Credit Accounts Payable 90."),
                                            option("books.cash", "Debit Inventory 80; Credit Cash 80."),
                                            option("books.revenue", "Debit Accounts Receivable 80; Credit Sales Revenue 80."),
                                            option("books.inventory_payable", "Debit Inventory 80; Credit Accounts Payable 80.")),
                                    "books.inventory_payable",
                                    "10 x 9 = 90 emeralds before the trade discount; 90 - 10 = 80 owed. "
                                    + "An immediate trade discount reduces inventory's purchase cost. Debit Inventory 80 and "
                                    + "credit Accounts Payable 80. This is a purchase, not a sale or a cash payment."),
                            new MultipleChoiceQuestion("inventory.lanterns_delivery", CONCEPT_ID,
                                    "A village trading shop receives five lanterns at 16 emeralds each for resale. "
                                    + "The supplier's invoice also includes 6 emeralds to deliver them to the shop. "
                                    + "The shop owes the entire invoice; nothing has been paid. Include delivery in inventory cost. Which entry is correct?",
                                    List.of(
                                            option("lanterns.inventory_payable", "Debit Inventory 86; Credit Accounts Payable 86."),
                                            option("lanterns.goods_only", "Debit Inventory 80; Credit Accounts Payable 80."),
                                            option("lanterns.cash", "Debit Inventory 86; Credit Cash 86."),
                                            option("lanterns.expense", "Debit Delivery Expense 86; Credit Accounts Payable 86.")),
                                    "lanterns.inventory_payable",
                                    "Five lanterns x 16 = 80 emeralds; add 6 delivery = 86. "
                                    + "Delivery to bring purchased goods to their location is part of inventory cost. "
                                    + "Debit Inventory 86 and credit Accounts Payable 86 for the wholly unpaid invoice."))),
            SupplierPaymentConcept.CONCEPT));

    private static AnswerOption option(String id, String text) { return new AnswerOption(id, text); }

    private InventoryCreditAssignment() { }
}
