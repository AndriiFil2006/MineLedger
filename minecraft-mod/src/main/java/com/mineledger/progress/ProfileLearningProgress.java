package com.mineledger.progress;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import com.mineledger.accounting.JournalProgress;
import com.mineledger.accounting.mastery.Assignment;
import com.mineledger.accounting.mastery.AssignmentProgress;
import com.mineledger.accounting.mastery.ConceptProgressionService;
import com.mineledger.accounting.mastery.LearningSession;
import com.mineledger.progress.ProfileProgress.SavedAssignment;

/** Application lifecycle boundary. Storage failures never escape into question callbacks. */
public final class ProfileLearningProgress {
    private final UUID profileId;
    private final ProgressRepository repository;
    private final List<Assignment> catalog;
    private final Consumer<String> reportError;
    private final LearningSession mastery;
    private final JournalProgress journal;
    private boolean writable = true;
    private boolean dirty;
    private String storageStatus = "Progress is saved locally for this profile.";

    public ProfileLearningProgress(UUID profileId, ProgressRepository repository, List<Assignment> catalog,
            Set<String> journalQuestionIds, Consumer<String> reportError) {
        this.profileId = profileId;
        this.repository = repository;
        this.catalog = List.copyOf(catalog);
        this.reportError = reportError;
        ProfileProgress restored;
        try {
            restored = repository.load(profileId).orElseGet(() -> ProfileProgress.empty(profileId));
            validate(restored, journalQuestionIds);
        } catch (IOException | IllegalArgumentException exception) {
            writable = false;
            storageStatus = "Local save could not be loaded. The existing file is preserved; progress is session-only until repaired. "
                    + exception.getMessage();
            reportError.accept(storageStatus);
            restored = ProfileProgress.empty(profileId);
        }
        var assignments = new HashMap<String, AssignmentProgress>();
        restored.assignments().forEach((id, state) -> assignments.put(id, state.progress()));
        mastery = new LearningSession(assignments, this::changed);
        journal = new JournalProgress(restored.journalQuestionIds(), this::changed);
    }

    public UUID profileId() { return profileId; }
    public LearningSession mastery() { return mastery; }
    public JournalProgress journal() { return journal; }
    public String storageStatus() { return storageStatus; }

    private void changed() {
        dirty = true;
        save();
    }

    public void save() {
        if (!writable || !dirty) return;
        try {
            repository.save(snapshot());
            dirty = false;
            storageStatus = "Progress is saved locally for this profile.";
        } catch (IOException exception) {
            if (exception instanceof ProgressFormatException) writable = false;
            storageStatus = "Progress could not be saved locally; current progress is still in memory. " + exception.getMessage();
            reportError.accept(storageStatus);
        }
    }

    public ProfileProgress snapshot() {
        var assignments = new HashMap<String, SavedAssignment>();
        int xp = journal.xp();
        for (Assignment assignment : catalog) {
            var service = mastery.open(assignment);
            Set<String> rewarded = rewardedConcepts(service);
            assignments.put(assignment.id(), new SavedAssignment(service.progress(),
                    service.currentConcept().map(concept -> concept.id()).orElse(null), rewarded, service.xp()));
            xp += service.xp();
        }
        return new ProfileProgress(profileId, assignments, journal.correctQuestionIds(), xp);
    }

    private void validate(ProfileProgress saved, Set<String> journalQuestionIds) {
        if (!profileId.equals(saved.profileId()) || !journalQuestionIds.containsAll(saved.journalQuestionIds())) {
            throw new IllegalArgumentException("Saved profile or journal questions do not match installed content");
        }
        Map<String, Assignment> known = catalog.stream().collect(Collectors.toMap(Assignment::id, assignment -> assignment));
        int xp = saved.journalQuestionIds().size() * 10;
        for (var entry : saved.assignments().entrySet()) {
            Assignment assignment = known.get(entry.getKey());
            if (assignment == null) throw new IllegalArgumentException("Saved assignment is not installed: " + entry.getKey());
            SavedAssignment state = entry.getValue();
            var service = new ConceptProgressionService(assignment, state.progress(), () -> { });
            String active = service.currentConcept().map(concept -> concept.id()).orElse(null);
            if (!Objects.equals(active, state.activeConceptId()) || state.awardedXp() != service.xp()
                    || !state.rewardedConceptIds().equals(rewardedConcepts(service))) {
                throw new IllegalArgumentException("Saved concept order or completion rewards are inconsistent");
            }
            xp += service.xp();
        }
        if (xp != saved.totalAwardedXp()) throw new IllegalArgumentException("Saved total XP does not match credited rewards");
    }

    private static Set<String> rewardedConcepts(ConceptProgressionService service) {
        return service.assignment().concepts().stream().filter(concept -> service.progress().concept(concept.id()).completed(concept))
                .map(concept -> concept.id()).collect(Collectors.toSet());
    }
}
