package com.mineledger.client;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import com.mineledger.MineLedger;
import com.mineledger.backend.BackendClient;
import com.mineledger.backend.BackendConfig;
import com.mineledger.backend.ConnectionManager;
import com.mineledger.backend.auth.AuthSettings;
import com.mineledger.backend.auth.LoopbackCallbackServer;
import com.mineledger.backend.auth.OidcHttpClient;
import com.mineledger.backend.auth.RefreshTokenStore;
import com.mineledger.backend.auth.SignInManager;
import com.mojang.blaze3d.Blaze3D;
import net.minecraft.client.Minecraft;

/**
 * Client-lifetime connection and account state. The preference comes from mineledger/config.json and can be
 * changed in MineLedger Settings without a restart; the backend URL and timeouts are read once at startup.
 * Signing in happens in the player's web browser; nothing about the account is ever written to the log.
 */
public final class BackendRuntime {
    private static ConnectionManager connection;
    private static Optional<String> configProblem = Optional.empty();
    private static BackendClient client;
    private static SignInManager account;
    private static OidcHttpClient identityProvider;

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
        identityProvider = new OidcHttpClient(config.connectTimeout(), config.requestTimeout());
        // Both HTTP clients are built on first use, so a player who stays in Offline Practice creates neither.
        account = new SignInManager(() -> {
            connection.api();
            return client;
        }, identityProvider, new LoopbackCallbackServer(), Blaze3D::openUri, RefreshTokenStore.file(gameDirectory),
                minecraft::execute, System::currentTimeMillis, AuthSettings.secure(config.baseUrl()));
        MineLedger.LOGGER.info("MineLedger connection preference: {} (server {})", config.mode().key(), config.baseUrl());
    }

    static void shutdown() {
        if (account != null) account.close();
        if (identityProvider != null) identityProvider.close();
        if (client != null) client.close();
    }

    public static ConnectionManager connection() { return connection; }

    /** Who is signed in to the MineLedger server. Independent of the Minecraft profile and of local practice. */
    public static SignInManager account() { return account; }

    /** Present when the config file was invalid at startup and default settings are in effect. */
    public static Optional<String> configProblem() { return configProblem; }

    private BackendRuntime() { }
}
