package com.kncatl.ohmyworld;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.kncatl.ohmyworld.platform.Platform;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

@SuppressWarnings("FieldMayBeFinal")
public class OhMyWorldConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static Path configPath() {
        return Platform.get().configDir().resolve("ohmyworld.json");
    }

    private boolean server_mode = false;
    private String formula = "y=0: minecraft:bedrock;y=1..64: (x+z)%2==0 ? minecraft:white_concrete : minecraft:gray_concrete";

    public boolean serverMode() { return server_mode; }
    public String formula() { return formula; }

    private static OhMyWorldConfig instance;

    public static OhMyWorldConfig load() {
        if (instance != null) return instance;
        Path configPath = configPath();

        if (Files.exists(configPath)) {
            try (Reader r = Files.newBufferedReader(configPath)) {
                instance = GSON.fromJson(r, OhMyWorldConfig.class);
                LOGGER.debug("Config loaded from {}", configPath);
                return instance;
            } catch (Exception e) {
                LOGGER.warn("Failed to load config, using defaults", e);
            }
        }

        instance = new OhMyWorldConfig();
        save();
        return instance;
    }

    public static void save() {
        try {
            Path configPath = configPath();
            Files.createDirectories(configPath.getParent());
            try (Writer w = Files.newBufferedWriter(configPath)) {
                GSON.toJson(instance != null ? instance : new OhMyWorldConfig(), w);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to save config", e);
        }
    }
}
