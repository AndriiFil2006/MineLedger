package com.mineledger.accounting.mastery;

import com.mineledger.curriculum.CurriculumContent;

/** Stable-ID handle for the original assignment; no duplicate content bank. */
public final class InventoryCreditAssignment {
    public static final String CONCEPT_ID = "inventory.purchases_on_credit";
    public static final Assignment ASSIGNMENT = CurriculumContent.get().courses().assignment("inventory_fundamentals").orElseThrow();
    private InventoryCreditAssignment() { }
}
