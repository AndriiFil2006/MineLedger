package com.mineledger.education;

import java.util.List;
import com.mineledger.accounting.mastery.Assignment;
import com.mineledger.accounting.mastery.InventoryCreditAssignment;

/** Organization only: all existing playable concepts and question IDs are preserved. */
public final class Accounting101 {
    public static final String COURSE_ID = "accounting101";
    public static final String UNIT_1_ID = "accounting101.fundamentals";
    public static final String UNIT_2_ID = "accounting101.recording_transactions";
    public static final String UNIT_3_ID = "accounting101.adjusting_entries";
    public static final Course COURSE = new Course(COURSE_ID, "Accounting 101 — Financial Accounting",
            "Explore financial accounting through Minecraft businesses, from fundamentals to adjusting entries.", List.of(
                    new Unit(UNIT_1_ID, COURSE_ID, 1, "Accounting Fundamentals", "Build the foundations of financial accounting.", List.of(
                            coming("accounting101.accounting_equation", UNIT_1_ID, "1.1", "The Accounting Equation"),
                            coming("accounting101.assets_liabilities_equity", UNIT_1_ID, "1.2", "Assets, Liabilities, and Equity"),
                            coming("accounting101.debit_credit_rules", UNIT_1_ID, "1.3", "Debit and Credit Rules"))),
                    new Unit(UNIT_2_ID, COURSE_ID, 2, "Recording Transactions", "Record transactions and organize accounting records.", List.of(
                            InventoryCreditAssignment.ASSIGNMENT,
                            coming("accounting101.posting_ledger", UNIT_2_ID, "2.2", "Posting to the Ledger"),
                            coming("accounting101.trial_balance", UNIT_2_ID, "2.3", "Trial Balance"))),
                    new Unit(UNIT_3_ID, COURSE_ID, 3, "Adjusting Entries", "Explore period-end accounting adjustments.", List.of(
                            coming("accounting101.accruals", UNIT_3_ID, "3.1", "Accruals"),
                            coming("accounting101.deferrals", UNIT_3_ID, "3.2", "Deferrals")))));
    public static final CourseCatalog CATALOG = new CourseCatalog(List.of(COURSE));

    private static Assignment coming(String id, String unitId, String number, String title) {
        return new Assignment(id, unitId, "Assignment " + number + ": " + title,
                "Coming Soon: " + title + ". No exercises are available yet.", Assignment.Availability.COMING_SOON, List.of());
    }

    private Accounting101() { }
}
