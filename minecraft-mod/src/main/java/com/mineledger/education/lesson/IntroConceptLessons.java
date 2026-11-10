package com.mineledger.education.lesson;

import java.util.List;
import com.mineledger.accounting.mastery.InventoryCreditAssignment;

/** One original, fixed lesson using the perpetual inventory system. */
public final class IntroConceptLessons {
    public static final ConceptLesson INVENTORY_ON_CREDIT = new ConceptLesson(
            "lesson.inventory.purchases_on_credit.perpetual", InventoryCreditAssignment.CONCEPT_ID,
            "Recording Inventory Purchases on Credit",
            "Record inventory acquired on credit using the perpetual inventory system.",
            "Inventory is an asset: it represents goods a business holds for resale. Under the perpetual inventory system, "
                    + "record a purchase directly in Inventory when the goods are acquired. Buying on credit means the supplier "
                    + "has not been paid yet. The business receives an asset and takes on a liability called Accounts Payable.",
            List.of("Inventory is an asset; goods bought for resale increase Inventory.",
                    "Assets increase with debits, so debit Inventory for the purchase cost.",
                    "Accounts Payable is a liability: an obligation to pay the supplier later.",
                    "Liabilities increase with credits, so credit Accounts Payable for the amount owed.",
                    "A credit purchase does not use cash at the purchase date.",
                    "Total debits must equal total credits."),
            List.of(new ConceptLesson.WorkedExample("Steve restocks his shop",
                    "Steve purchases 4 diamond shovels at 15 emeralds each on credit for resale. He pays nothing today.",
                    "4 × 15 = 60 emeralds.", "Debit Inventory 60.", "Credit Accounts Payable 60.",
                    "The debit increases the shop's inventory asset by 60 emeralds. The credit increases the amount owed "
                            + "to the supplier by 60. Both sides balance. Paying the supplier later is a separate transaction.")),
            List.of("Do not credit Cash: no emeralds leave the business when this credit purchase is recorded.",
                    "Do not debit Equipment: these shovels are held for resale. Tools kept for the shop's own long-term use "
                            + "would be considered differently; the purpose of the purchase matters.",
                    "Do not record a purchase expense instead of Inventory for this perpetual-inventory example."),
            "Goods for resale received now, payment owed later: debit Inventory and credit Accounts Payable for the same cost.");
    public static final ConceptLessonCatalog CATALOG = new ConceptLessonCatalog(List.of(INVENTORY_ON_CREDIT));

    private IntroConceptLessons() { }
}
