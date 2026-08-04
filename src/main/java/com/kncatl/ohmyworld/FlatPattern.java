package com.kncatl.ohmyworld;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;

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

            copyGuideIfNeeded(dir, "zh_cn", "README_zh_cn.md", "/assets/ohmyworld/doc/guide.txt");
            copyGuideIfNeeded(dir, "en_us", "README_en_us.md", "/assets/ohmyworld/doc/guide_en.txt");
            LOGGER.debug("README files checked at {}", dir);
        } catch (Exception e) {
            LOGGER.warn("Failed to create ohmyworld/README", e);
        }
    }

    /**
     * 将内置指南拷贝到游戏目录。目标文件不存在时直接写入；
     * 已存在时仅当“上次由我们写入且之后未被修改”才覆盖更新，
     * 保证模组升级后指南能自动更新，同时不破坏用户自行编辑过的文件。
     * 判断依据是随文件一起写入的 SHA-256 标记文件（.guide_&lt;lang&gt;.sha256）。
     */
    private static void copyGuideIfNeeded(Path dir, String lang, String fileName, String resourcePath) throws Exception {
        Path target = dir.resolve(fileName);
        Path marker = dir.resolve(".guide_" + lang + ".sha256");

        byte[] bundled;
        try (InputStream in = FlatPattern.class.getResourceAsStream(resourcePath)) {
            if (in == null) return;
            bundled = in.readAllBytes();
        }
        String bundledSha = sha256Hex(bundled);

        if (Files.isSymbolicLink(target) || Files.isSymbolicLink(marker)) {
            LOGGER.warn("Refusing to update symbolic-link guide file {}", target);
            return;
        }

        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            writeAtomically(target, bundled);
            writeAtomically(marker, bundledSha.getBytes(StandardCharsets.UTF_8));
            return;
        }

        String recorded = Files.exists(marker, LinkOption.NOFOLLOW_LINKS) ? Files.readString(marker).trim() : null;
        String currentSha = sha256Hex(Files.readAllBytes(target));

        if (currentSha.equals(bundledSha)) {
            // 内容已是最新（如同版本重装）：仅确保标记存在
            writeAtomically(marker, bundledSha.getBytes(StandardCharsets.UTF_8));
        } else if (recorded != null && recorded.equals(currentSha)) {
            // 文件自上次写入后未被修改：安全覆盖更新
            writeAtomically(target, bundled);
            writeAtomically(marker, bundledSha.getBytes(StandardCharsets.UTF_8));
        }
        // 其余情况（无标记的旧安装、用户修改过的文件）：保持原文件不动
    }

    private static String sha256Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
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
