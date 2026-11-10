package com.mineledger.accounting.mastery;

import com.mineledger.curriculum.CurriculumContent;

/** Stable-ID handle; the concept and its questions are loaded from JSON. */
public final class SupplierPaymentConcept {
    public static final String CONCEPT_ID = "payables.cash_payments";
    public static final AccountingConcept CONCEPT = CurriculumContent.get().concepts().get(CONCEPT_ID);
    private SupplierPaymentConcept() { }
}
