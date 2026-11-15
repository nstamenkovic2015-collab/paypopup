package com.paypopup;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Settings saved to .minecraft/config/paypopup.json.
 * Edit that file to change how the popup behaves.
 */
public class PayPopupConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("paypopup.json");

    /** Turn the whole mod on or off. */
    public boolean enabled = true;

    /** Play a sound when the popup shows. */
    public boolean playSound = true;

    /** Payments smaller than this don't get a popup (0 = show every payment). */
    public double minimumAmount = 0;

    /** How long the popup stays on screen, in seconds. */
    public float displaySeconds = 3.5f;

    /** Size of the popup (1.0 = normal). */
    public float scale = 1.0f;

    private static PayPopupConfig instance;

    public static PayPopupConfig get() {
        if (instance == null) load();
        return instance;
    }

    public static void load() {
        if (Files.exists(FILE)) {
            try (Reader reader = Files.newBufferedReader(FILE)) {
                instance = GSON.fromJson(reader, PayPopupConfig.class);
            } catch (Exception e) {
                PayPopupClient.LOGGER.warn("Couldn't read paypopup.json, using defaults", e);
            }
        }
        if (instance == null) instance = new PayPopupConfig();
        save();
    }

    public static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(FILE)) {
                GSON.toJson(instance, writer);
            }
        } catch (IOException e) {
            PayPopupClient.LOGGER.warn("Couldn't save paypopup.json", e);
        }
    }
}
