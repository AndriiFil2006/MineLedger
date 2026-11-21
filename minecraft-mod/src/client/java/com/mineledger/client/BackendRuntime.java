package com.mineledger.client;

import java.nio.file.Path;
import java.util.Optional;
import com.mineledger.MineLedger;
import com.mineledger.backend.BackendClient;
import com.mineledger.backend.BackendConfig;
import com.mineledger.backend.CurriculumSource;
import com.mineledger.curriculum.CurriculumContent;

/** The content mode chosen once at client startup from mineledger/config.json; changing it needs a restart. */
public final class BackendRuntime {
    private static CurriculumSource source = new CurriculumSource.Local(CurriculumContent.get());
    private static Optional<String> configProblem = Optional.empty();
    private static BackendClient client;

    static void initialize(Path gameDirectory) {
        BackendConfig.Loaded loaded = BackendConfig.load(gameDirectory);
        configProblem = loaded.problem();
        configProblem.ifPresent(problem -> MineLedger.LOGGER.warn("MineLedger backend config: {}", problem));
        source = CurriculumSource.select(loaded.config(), CurriculumContent::get, config -> client = new BackendClient(config));
        if (source instanceof CurriculumSource.Remote) {
            MineLedger.LOGGER.info("MineLedger backend mode: read-only curriculum from {}", loaded.config().baseUrl());
        }
    }

    static void shutdown() {
        if (client != null) client.close();
    }

    public static CurriculumSource source() { return source; }

    /** Present when the config file was invalid and offline defaults are in effect. */
    public static Optional<String> configProblem() { return configProblem; }

    private BackendRuntime() { }
}
