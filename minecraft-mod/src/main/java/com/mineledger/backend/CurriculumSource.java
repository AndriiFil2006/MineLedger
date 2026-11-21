package com.mineledger.backend;

import java.util.function.Function;
import java.util.function.Supplier;
import com.mineledger.curriculum.Curriculum;

/**
 * Where the browsable curriculum comes from. Exactly one provider is chosen from configuration at startup and
 * it never changes while the game runs: a backend failure is shown as a failure, never replaced by local content.
 */
public sealed interface CurriculumSource {
    /** Bundled JSON curriculum with the complete local grading, mastery, XP and Recharge workflow. */
    record Local(Curriculum curriculum) implements CurriculumSource { }

    /** Backend curriculum, read-only: browsing and question preview without answer keys, grading or progress. */
    record Remote(CurriculumApi api) implements CurriculumSource { }

    /** Offline mode never constructs a backend client, so it cannot make a network request. */
    static CurriculumSource select(BackendConfig config, Supplier<Curriculum> local,
            Function<BackendConfig, ? extends CurriculumApi> remote) {
        return switch (config.mode()) {
            case OFFLINE -> new Local(local.get());
            case BACKEND -> new Remote(remote.apply(config));
        };
    }
}
