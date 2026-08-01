package com.kncatl.ohmyworld;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import net.neoforged.fml.common.Mod;

import com.kncatl.ohmyworld.platform.Platform;
import com.kncatl.ohmyworld.platform.neoforge.NeoForgePlatform;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

@Mod(FlatPattern.MODID)
public class FlatPattern {
    public static final String MODID = "ohmyworld";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean initialized;

    public FlatPattern() {
        if (!initialized) {
            initialized = true;
            Platform.set(new NeoForgePlatform());
            OhMyWorldConfig.load();
            copyGuideFiles();
            WorldLoadHandler.register();
        }
    }

    private static void copyGuideFiles() {
        try {
            Path dir = Platform.get().gameDir().resolve("ohmyworld");
            Files.createDirectories(dir);

            Path zh = dir.resolve("README_zh_cn.md");
            if (!Files.exists(zh)) {
                try (InputStream in = FlatPattern.class.getResourceAsStream("/assets/ohmyworld/doc/guide.txt")) {
                    if (in != null) Files.copy(in, zh);
                }
            }

            Path en = dir.resolve("README_en_us.md");
            if (!Files.exists(en)) {
                try (InputStream in = FlatPattern.class.getResourceAsStream("/assets/ohmyworld/doc/guide_en.txt")) {
                    if (in != null) Files.copy(in, en);
                }
            }
            LOGGER.debug("README files created at {}", dir);
        } catch (Exception e) {
            LOGGER.warn("Failed to create ohmyworld/README", e);
        }
    }
}