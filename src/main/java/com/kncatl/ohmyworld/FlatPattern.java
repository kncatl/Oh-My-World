package com.kncatl.ohmyworld;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

//? if NEOFORGE {
import net.neoforged.fml.common.Mod;
//?}

import com.kncatl.ohmyworld.platform.Platform;
//? if NEOFORGE {
import com.kncatl.ohmyworld.platform.neoforge.NeoForgePlatform;
//?}
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

//? if NEOFORGE {
@Mod(FlatPattern.MODID)
//?}
public class FlatPattern {
    public static final String MODID = "ohmyworld";
    private static final Logger LOGGER = LogUtils.getLogger();

    //? if NEOFORGE {
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
    //?}

    public static void copyGuideFiles() {
        try {
            Path dir = Platform.get().gameDir().resolve("ohmyworld");
            Files.createDirectories(dir);
            copyGuide(dir, "guide.md", "/assets/ohmyworld/doc/guide.md");
            copyGuide(dir, "guide_en.md", "/assets/ohmyworld/doc/guide_en.md");
            LOGGER.debug("Guide files written to {}", dir);
        } catch (Exception e) {
            LOGGER.warn("Failed to create ohmyworld guide files", e);
        }
    }

    /** 启动时释放内置指南：同名文件直接覆盖（用户自定义内容请另存为其他文件名）。 */
    private static void copyGuide(Path dir, String fileName, String resourcePath) throws Exception {
        Path target = dir.resolve(fileName);
        if (Files.isSymbolicLink(target)) {
            LOGGER.warn("Refusing to replace symbolic-link guide file {}", target);
            return;
        }
        byte[] bundled;
        try (InputStream in = FlatPattern.class.getResourceAsStream(resourcePath)) {
            if (in == null) return;
            bundled = in.readAllBytes();
        }
        writeAtomically(target, bundled);
    }

    private static void writeAtomically(Path target, byte[] content) throws Exception {
        if (Files.isSymbolicLink(target)) throw new IllegalStateException("symbolic-link target");
        Path parent = target.toAbsolutePath().getParent();
        Path temp = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
        try {
            Files.write(temp, content);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
