package com.kncatl.ohmyworld;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.kncatl.ohmyworld.platform.Platform;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@SuppressWarnings("FieldMayBeFinal")
public class OhMyWorldConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String DEFAULT_FORMULA = PatternData.DEFAULT_INPUT;

    private static volatile OhMyWorldConfig instance;
    /** 上次读取/写入时的配置文件修改时间，用于运行中热加载检测 */
    private static volatile long lastModified = -1;

    private static Path configPath() {
        return Platform.get().configDir().resolve("ohmyworld.json");
    }

    private boolean server_mode = false;
    private String formula = DEFAULT_FORMULA;

    public boolean serverMode() { return server_mode; }
    public String formula() { return formula; }

    public static OhMyWorldConfig load() {
        if (instance != null) return instance;
        return reload();
    }

    /** 强制重新从磁盘读取配置（世界加载时调用）。
     *  文件损坏/缺失字段时使用默认值，但不覆写原文件，避免丢失用户数据。 */
    public static OhMyWorldConfig reload() {
        instance = null;
        Path configPath = configPath();

        if (Files.isSymbolicLink(configPath)) {
            LOGGER.error("ohmyworld: refusing to read symbolic-link config {}", configPath);
            instance = new OhMyWorldConfig();
            return instance;
        }

        if (Files.exists(configPath, LinkOption.NOFOLLOW_LINKS)) {
            try (Reader r = Files.newBufferedReader(configPath)) {
                OhMyWorldConfig parsed = GSON.fromJson(r, OhMyWorldConfig.class);
                if (parsed == null) throw new IllegalStateException("empty config file");
                if (parsed.formula == null || parsed.formula.isBlank()) parsed.formula = DEFAULT_FORMULA;
                instance = parsed;
                lastModified = Files.getLastModifiedTime(configPath, LinkOption.NOFOLLOW_LINKS).toMillis();
                LOGGER.debug("Config loaded from {}", configPath);
                return instance;
            } catch (Exception e) {
                // 解析失败：使用默认值，但保留原文件供用户修复
                LOGGER.error("ohmyworld: failed to parse config {}, using defaults (original file kept)", configPath, e);
            }
            instance = new OhMyWorldConfig();
            return instance;
        }

        instance = new OhMyWorldConfig();
        save();
        return instance;
    }

    public static void save() {
        try {
            Path configPath = configPath();
            Files.createDirectories(configPath.getParent());
            if (Files.isSymbolicLink(configPath)) {
                throw new IllegalStateException("refusing to replace symbolic-link config " + configPath);
            }
            Path temp = Files.createTempFile(configPath.getParent(), configPath.getFileName().toString(), ".tmp");
            try {
                try (Writer w = Files.newBufferedWriter(temp)) {
                    GSON.toJson(instance != null ? instance : new OhMyWorldConfig(), w);
                }
                Files.move(temp, configPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, configPath, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
            lastModified = Files.getLastModifiedTime(configPath, LinkOption.NOFOLLOW_LINKS).toMillis();
        } catch (Exception e) {
            LOGGER.warn("Failed to save config", e);
        }
    }

    /** 运行中热加载：仅当配置文件修改时间变化时重新读取，返回是否发生了变化。
     *  由服务器 tick 定期调用；只有 server_mode 下才需要真正应用新公式。 */
    public static boolean reloadIfChanged() {
        try {
            Path path = configPath();
            if (Files.isSymbolicLink(path) || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false;
            long t = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis();
            if (t == lastModified) return false;
            reload();
            lastModified = t;
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
