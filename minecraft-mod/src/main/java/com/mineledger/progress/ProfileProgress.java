package com.mineledger.progress;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.mineledger.accounting.mastery.AssignmentProgress;

/** Saved reward ledger is checked against distinct credits before restoration. */
public record ProfileProgress(UUID profileId, Map<String, SavedAssignment> assignments,
        Set<String> journalQuestionIds, int totalAwardedXp) {
    public ProfileProgress {
        assignments = Map.copyOf(assignments);
        journalQuestionIds = Set.copyOf(journalQuestionIds);
    }

    public record SavedAssignment(AssignmentProgress progress, String activeConceptId,
            Set<String> rewardedConceptIds, int awardedXp) {
        public SavedAssignment { rewardedConceptIds = Set.copyOf(rewardedConceptIds); }
    }

    public static ProfileProgress empty(UUID profileId) { return new ProfileProgress(profileId, Map.of(), Set.of(), 0); }
}
