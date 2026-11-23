package com.mineledger.client;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import com.mineledger.MineLedger;
import com.mineledger.backend.BackendClient;
import com.mineledger.backend.BackendConfig;
import com.mineledger.backend.ConnectionManager;
import net.minecraft.client.Minecraft;

/**
 * Client-lifetime connection state. The preference comes from mineledger/config.json and can be changed in
 * MineLedger Settings without a restart; the backend URL and timeouts are read once at startup.
 */
public final class BackendRuntime {
    private static ConnectionManager connection;
    private static Optional<String> configProblem = Optional.empty();
    private static BackendClient client;

    static void initialize(Path gameDirectory) {
        BackendConfig.Loaded loaded = BackendConfig.load(gameDirectory);
        BackendConfig config = loaded.config();
        configProblem = loaded.problem();
        configProblem.ifPresent(problem -> MineLedger.LOGGER.warn("MineLedger config: {}", problem));
        Minecraft minecraft = Minecraft.getInstance();
        // The HTTP client is only built if a connection check is ever needed (never in Offline Practice).
        connection = new ConnectionManager(config.mode(), () -> client = new BackendClient(config), minecraft::execute,
                System::currentTimeMillis, preference -> {
                    try {
                        config.withMode(preference).save(gameDirectory);
                        configProblem = Optional.empty();
                        return Optional.empty();
                    } catch (IOException | RuntimeException exception) {
                        MineLedger.LOGGER.warn("MineLedger config could not be saved: {}", exception.toString());
                        return Optional.of("This choice could not be saved, so it only lasts until you close the game.");
                    }
                });
        MineLedger.LOGGER.info("MineLedger connection preference: {} (server {})", config.mode().key(), config.baseUrl());
    }

    static void shutdown() {
        if (client != null) client.close();
    }

    public static ConnectionManager connection() { return connection; }

    /** Present when the config file was invalid at startup and default settings are in effect. */
    public static Optional<String> configProblem() { return configProblem; }

    private BackendRuntime() { }
}
