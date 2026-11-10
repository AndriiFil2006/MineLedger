package com.mineledger.progress;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

public interface ProgressRepository {
    Optional<ProfileProgress> load(UUID profileId) throws IOException;
    void save(ProfileProgress progress) throws IOException;
}
