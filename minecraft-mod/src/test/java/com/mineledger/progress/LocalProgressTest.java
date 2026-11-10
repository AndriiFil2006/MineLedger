package com.mineledger.progress;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import com.mineledger.accounting.Account;
import com.mineledger.accounting.AccountingAnswer;
import com.mineledger.accounting.IntroJournalAssignment;
import com.mineledger.accounting.mastery.Assignment;
import com.mineledger.accounting.mastery.InventoryCreditAssignment;
import com.mineledger.accounting.mastery.MultipleChoiceQuestion;

public class LocalProgressTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final UUID profile = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private final Assignment assignment = InventoryCreditAssignment.ASSIGNMENT;
    private final Set<String> journalIds = IntroJournalAssignment.QUESTIONS.stream().map(question -> question.id()).collect(Collectors.toSet());
    private final List<String> warnings = new ArrayList<>();

    private Path gameDirectory() { return temporary.getRoot().toPath(); }
    private JsonProgressRepository repository() { return new JsonProgressRepository(gameDirectory()); }
    private ProfileLearningProgress open(UUID id) {
        return new ProfileLearningProgress(id, repository(), List.of(assignment), journalIds, warnings::add);
    }
    private ProfileLearningProgress open() { return open(profile); }

    private MultipleChoiceQuestion question(int concept, int question) { return assignment.concepts().get(concept).questions().get(question); }

    private void correct(ProfileLearningProgress learning, int concept, int question) {
        MultipleChoiceQuestion item = question(concept, question);
        learning.mastery().open(assignment).submit(item.id(), item.correctOptionId());
    }

    @Test
    public void savingAndRestoringUsesStableIdsIncludingHalfMasteredConcept() throws Exception {
        var learning = open();
        correct(learning, 0, 0);
        var restored = open();
        assertEquals(learning.snapshot(), restored.snapshot());
        var service = restored.mastery().open(assignment);
        assertEquals(1, service.progress().concept(assignment.concepts().getFirst().id()).credits());
        assertTrue(service.progress().concept(assignment.concepts().getFirst().id()).correctQuestionIds().contains(question(0, 0).id()));
        assertEquals(question(0, 1), service.nextQuestion().orElseThrow());
        assertEquals(0, service.xp());
        assertEquals(assignment.concepts().getFirst().id(), repository().load(profile).orElseThrow()
                .assignments().get(assignment.id()).activeConceptId());
        assertTrue(warnings.isEmpty());
    }

    @Test
    public void completedConceptRewardAndActiveConceptSurviveReloadWithoutDuplicateXp() throws Exception {
        var learning = open();
        correct(learning, 0, 0);
        correct(learning, 0, 1);
        var restored = open();
        var service = restored.mastery().open(assignment);
        assertEquals(1, service.completedConcepts());
        assertEquals(20, service.xp());
        assertEquals(assignment.concepts().get(1), service.currentConcept().orElseThrow());
        assertEquals(0, service.submit(question(0, 0).id(), question(0, 0).correctOptionId()).xpAwarded());
        correct(restored, 1, 0);
        correct(restored, 1, 1);
        var finalReload = open().mastery().open(assignment);
        assertTrue(finalReload.completed());
        assertEquals(40, finalReload.xp());
        assertEquals(0, finalReload.submit(question(1, 1).id(), question(1, 1).correctOptionId()).xpAwarded());
        var saved = repository().load(profile).orElseThrow();
        assertEquals(40, saved.totalAwardedXp());
        assertNull(saved.assignments().get(assignment.id()).activeConceptId());
        assertEquals(2, saved.assignments().get(assignment.id()).rewardedConceptIds().size());
    }

    @Test
    public void incorrectAnswersAndNavigationRetainCreditsAndSelectionHistoryAfterRestart() {
        var learning = open();
        correct(learning, 0, 0);
        var missed = question(0, 1);
        String wrong = missed.options().stream().filter(option -> !option.id().equals(missed.correctOptionId())).findFirst().orElseThrow().id();
        learning.mastery().open(assignment).submit(missed.id(), wrong);
        var snapshot = learning.snapshot();
        assertSame(learning.mastery().open(assignment), learning.mastery().open(assignment));
        assertEquals(snapshot, open().snapshot());
        assertEquals(question(0, 2), open().mastery().open(assignment).nextQuestion().orElseThrow());
    }

    @Test
    public void journalPracticeAlsoPersistsAndCannotAwardXpAgain() {
        var learning = open();
        var question = IntroJournalAssignment.QUESTIONS.getFirst();
        var answer = new AccountingAnswer(Account.INVENTORY, Account.ACCOUNTS_PAYABLE, "60");
        learning.journal().submit(question, answer);
        var restored = open();
        assertEquals(10, restored.journal().xp());
        restored.journal().submit(question, answer);
        assertEquals(10, restored.snapshot().totalAwardedXp());
        assertEquals(1, restored.journal().correctCount());
    }

    @Test
    public void missingSaveStartsFreshWithoutCreatingOrWritingAnEmptyFile() throws Exception {
        assertTrue(repository().load(profile).isEmpty());
        assertEquals(0, open().snapshot().totalAwardedXp());
        open().save();
        assertFalse(Files.exists(repository().file(profile)));
        assertTrue(warnings.isEmpty());
    }

    @Test
    public void corruptedFileIsNeverOverwrittenBySessionProgress() throws Exception {
        Files.createDirectories(repository().file(profile).getParent());
        String corrupt = "{\"schemaVersion\": 1, broken user data";
        Files.writeString(repository().file(profile), corrupt);
        var learning = open();
        correct(learning, 0, 0);
        learning.save();
        assertEquals(corrupt, Files.readString(repository().file(profile)));
        assertEquals(1, learning.mastery().open(assignment).progress().concept(assignment.concepts().getFirst().id()).credits());
        assertFalse(warnings.isEmpty());
        assertTrue(learning.storageStatus().contains("session-only"));
    }

    @Test
    public void oldAndFutureSchemasArePreservedWithoutAutomaticReset() throws Exception {
        correct(open(), 0, 0);
        String current = Files.readString(repository().file(profile));
        for (int version : new int[] {0, 2, 999}) {
            String unsupported = current.replace("\"schemaVersion\": 1", "\"schemaVersion\": " + version);
            Files.writeString(repository().file(profile), unsupported);
            var learning = open();
            correct(learning, 0, 1);
            assertEquals(unsupported, Files.readString(repository().file(profile)));
            assertTrue(learning.storageStatus().contains("Unsupported progress schema"));
        }
    }

    @Test
    public void profilesHaveIndependentUuidOnlyFiles() throws Exception {
        UUID other = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        correct(open(), 0, 0);
        assertEquals(0, open(other).mastery().open(assignment).progress().concept(assignment.concepts().getFirst().id()).credits());
        correct(open(other), 0, 1);
        assertNotEquals(open().snapshot().assignments(), open(other).snapshot().assignments());
        assertEquals(profile + ".json", repository().file(profile).getFileName().toString());
        assertEquals(other + ".json", repository().file(other).getFileName().toString());
        assertTrue(repository().file(profile).normalize().startsWith(gameDirectory().toAbsolutePath()));
    }

    @Test
    public void profileMismatchAndInvalidRewardLedgerArePreserved() throws Exception {
        var learning = open();
        correct(learning, 0, 0);
        String valid = Files.readString(repository().file(profile));
        for (String invalid : List.of(valid.replace(profile.toString(), UUID.randomUUID().toString()),
                valid.replace("\"totalAwardedXp\": 0", "\"totalAwardedXp\": 500"))) {
            Files.writeString(repository().file(profile), invalid);
            var restored = open();
            correct(restored, 0, 1);
            assertEquals(invalid, Files.readString(repository().file(profile)));
            assertTrue(restored.storageStatus().contains("preserved"));
        }
    }

    @Test
    public void unknownCreditedQuestionIsRejectedWithoutDiscardingUserData() throws Exception {
        correct(open(), 0, 0);
        String invalid = Files.readString(repository().file(profile)).replace(question(0, 0).id(), "removed.question");
        Files.writeString(repository().file(profile), invalid);
        correct(open(), 0, 1);
        assertEquals(invalid, Files.readString(repository().file(profile)));
        assertFalse(warnings.isEmpty());
    }

    @Test
    public void duplicateFieldsAndTruncatedJsonCannotBeOverwrittenEvenViaRepositoryDirectly() throws Exception {
        Files.createDirectories(repository().file(profile).getParent());
        for (String invalid : List.of("{\"schemaVersion\":1,\"schemaVersion\":1}", "null", "[]", "{", "{}")) {
            Files.writeString(repository().file(profile), invalid);
            assertThrows(IOException.class, () -> repository().save(ProfileProgress.empty(profile)));
            assertEquals(invalid, Files.readString(repository().file(profile)));
        }
    }

    @Test
    public void externalCorruptionAfterLoadIsNotReplacedDuringAutosave() throws Exception {
        var learning = open();
        correct(learning, 0, 0);
        Files.writeString(repository().file(profile), "external corrupted save");
        correct(learning, 0, 1);
        assertEquals("external corrupted save", Files.readString(repository().file(profile)));
        assertEquals(20, learning.mastery().open(assignment).xp());
        assertTrue(learning.storageStatus().contains("could not be saved"));
    }

    @Test
    public void completedSaveIsValidJsonAndLeavesNoTemporaryFiles() throws Exception {
        var learning = open();
        correct(learning, 0, 0);
        correct(learning, 0, 1);
        assertEquals(learning.snapshot(), repository().load(profile).orElseThrow());
        try (var paths = Files.list(repository().file(profile).getParent())) {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    public void externalValidChangesArePreservedInsteadOfLosingAnotherClientsProgress() throws Exception {
        var first = open();
        var second = open();
        correct(first, 0, 0);
        String savedByFirst = Files.readString(repository().file(profile));
        correct(second, 0, 1);
        assertEquals(savedByFirst, Files.readString(repository().file(profile)));
        assertTrue(second.storageStatus().contains("changed outside this client"));
    }

    @Test
    public void temporaryWriteFailuresKeepMemoryAndRetryDirtyProgress() throws Exception {
        class FailingOnce implements ProgressRepository {
            boolean fail = true;
            final JsonProgressRepository files = repository();
            public java.util.Optional<ProfileProgress> load(UUID id) throws IOException { return files.load(id); }
            public void save(ProfileProgress state) throws IOException {
                if (fail) { fail = false; throw new IOException("Disk temporarily unavailable"); }
                files.save(state);
            }
        }
        var learning = new ProfileLearningProgress(profile, new FailingOnce(), List.of(assignment), journalIds, warnings::add);
        correct(learning, 0, 0);
        assertTrue(learning.storageStatus().contains("could not be saved"));
        assertFalse(Files.exists(repository().file(profile)));
        learning.save();
        assertEquals(learning.snapshot(), open().snapshot());
        assertFalse(learning.storageStatus().contains("could not"));
    }

    @Test
    public void rechargeAfterReloadDoesNotModifySavedProfileOrRewards() throws Exception {
        var learning = open();
        correct(learning, 0, 0);
        correct(learning, 0, 1);
        correct(learning, 1, 0);
        correct(learning, 1, 1);
        String before = Files.readString(repository().file(profile));
        var restored = open();
        var snapshot = restored.snapshot();
        var practice = new com.mineledger.accounting.mastery.RechargePractice(restored.mastery().open(assignment));
        while (!practice.completed()) {
            practice.submit(practice.currentQuestion().orElseThrow().correctOptionId());
            practice.continuePractice();
        }
        restored.save();
        assertEquals(snapshot, restored.snapshot());
        assertEquals(before, Files.readString(repository().file(profile)));
        assertEquals(40, open().snapshot().totalAwardedXp());
    }

    @Test
    public void idleAndDuplicateSubmissionsDoNotWriteEveryFrameOrAgainOnShutdown() throws Exception {
        class CountingRepository implements ProgressRepository {
            int writes;
            final JsonProgressRepository files = repository();
            public java.util.Optional<ProfileProgress> load(UUID id) throws IOException { return files.load(id); }
            public void save(ProfileProgress state) throws IOException { writes++; files.save(state); }
        }
        var repository = new CountingRepository();
        var learning = new ProfileLearningProgress(profile, repository, List.of(assignment), journalIds, warnings::add);
        for (int i = 0; i < 20; i++) learning.save();
        assertEquals(0, repository.writes);
        correct(learning, 0, 0);
        assertEquals(1, repository.writes);
        correct(learning, 0, 0);
        learning.save();
        assertEquals(1, repository.writes);
    }
}
