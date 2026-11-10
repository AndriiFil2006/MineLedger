package com.mineledger.client;

import com.mineledger.accounting.JournalProgress;
import com.mineledger.accounting.mastery.LearningSession;
import com.mineledger.accounting.IntroJournalAssignment;
import com.mineledger.education.Accounting101;
import com.mineledger.progress.JsonProgressRepository;
import com.mineledger.progress.ProfileLearningProgress;
import com.mineledger.MineLedger;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;

/** Lazy profile-scoped application state. Files are never opened from rendering callbacks. */
public final class JournalSession {
    private static ProfileLearningProgress current;
    private static final Map<UUID, ProfileLearningProgress> profiles = new HashMap<>();

    private static ProfileLearningProgress current() {
        Minecraft client = Minecraft.getInstance();
        var user = client.getUser();
        UUID id = user.getProfileId();
        if (id == null) id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + user.getName()).getBytes(StandardCharsets.UTF_8));
        if (current == null || !current.profileId().equals(id)) {
            save();
            current = profiles.computeIfAbsent(id, profileId -> new ProfileLearningProgress(profileId,
                    new JsonProgressRepository(client.gameDirectory.toPath()),
                    Accounting101.CATALOG.availableAssignments(), IntroJournalAssignment.QUESTIONS.stream()
                            .map(question -> question.id()).collect(Collectors.toSet()),
                    message -> MineLedger.LOGGER.warn("MineLedger progress: {}", message)));
        }
        return current;
    }

    public static JournalProgress progress() { return current().journal(); }
    public static LearningSession mastery() { return current().mastery(); }
    public static String storageStatus() { return current().storageStatus(); }
    public static void save() { profiles.values().forEach(ProfileLearningProgress::save); }

    private JournalSession() {
    }
}
