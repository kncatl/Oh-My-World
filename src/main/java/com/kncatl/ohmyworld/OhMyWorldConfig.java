package com.kncatl.ohmyworld;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.kncatl.ohmyworld.platform.Platform;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
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
    /** formula_file 解析后的实际路径与修改时间（用于 txt 公式热加载；无文件时为 null/-1）。 */
    private static volatile Path formulaSourcePath;
    private static volatile long formulaSourceModified = -1;

    private static Path configPath() {
        return Platform.get().configDir().resolve("ohmyworld.json");
    }

    private boolean server_mode = false;
    private String formula = DEFAULT_FORMULA;
    /**
     * 可选：公式 txt 文件路径（绝对路径，或相对服务器目录、ohmyworld/、config/ 目录）。
     * 非空时优先于 {@link #formula}；文件内容按 UTF-8 读取，换行统一为 LF。
     * 复杂公式建议用本字段——JSON 字符串里的换行和引号必须转义，直接粘贴很容易损坏配置。
     */
    private String formula_file = "";
    /** 诊断日志开关：为 true 时输出每个维度的接管决定、marker 读写、客户端替换决定等。 */
    private boolean debug_logs = false;
    /** 加载失败（JSON 损坏 / formula_file 读不到）：server_mode 下不在错误配置上生成世界。 */
    private transient boolean loadFailed = false;

    public boolean serverMode() { return server_mode; }
    public String formula() { return formula; }
    public String formulaFile() { return formula_file; }
    public boolean debugLogs() { return debug_logs; }
    public boolean loadFailed() { return loadFailed; }

    /** 当前配置是否开启了诊断日志（供各处的日志门控使用；未加载时按需读取一次）。 */
    public static boolean debugLogsEnabled() {
        OhMyWorldConfig config = instance;
        if (config == null) config = load();
        return config != null && config.debugLogs();
    }

    public static OhMyWorldConfig load() {
        if (instance != null) return instance;
        return reload();
    }

    /** 强制重新从磁盘读取配置（世界加载时调用）。
     *  文件损坏 / formula_file 读不到时标记 {@link #loadFailed}，不覆写原文件；
     *  server_mode 下由 WorldLoadHandler 拒绝用默认公式生成世界。 */
    public static OhMyWorldConfig reload() {
        instance = null;
        formulaSourcePath = null;
        formulaSourceModified = -1;
        Path configPath = configPath();

        if (Files.isSymbolicLink(configPath)) {
            LOGGER.error("ohmyworld: refusing to read symbolic-link config {}", configPath);
            instance = new OhMyWorldConfig();
            instance.loadFailed = true;
            return instance;
        }

        if (Files.exists(configPath, LinkOption.NOFOLLOW_LINKS)) {
            long configModified = -1;
            try {
                configModified = Files.getLastModifiedTime(configPath, LinkOption.NOFOLLOW_LINKS).toMillis();
                String json = Files.readString(configPath, StandardCharsets.UTF_8);
                if (!json.isEmpty() && json.charAt(0) == '\uFEFF') json = json.substring(1);
                OhMyWorldConfig parsed = GSON.fromJson(json, OhMyWorldConfig.class);
                if (parsed == null) throw new IllegalStateException("empty config file");

                if (parsed.formula_file != null && !parsed.formula_file.isBlank()) {
                    Path source = resolveFormulaPath(parsed.formula_file.trim(), parsed.server_mode);
                    if (source == null) {
                        throw new IllegalStateException("invalid formula_file path: " + parsed.formula_file);
                    }
                    formulaSourcePath = source;
                    String text = readFormulaFile(source);
                    parsed.formula = text;
                    formulaSourceModified = Files.getLastModifiedTime(source).toMillis();
                    LOGGER.info("ohmyworld: formula loaded from {} ({} chars)",
                            source.toAbsolutePath(), text.length());
                } else if (parsed.formula == null || parsed.formula.isBlank()) {
                    parsed.formula = DEFAULT_FORMULA;
                }

                // 把 formula_file 的路径保留在实例里（诊断/保存用），但 formula 已是文件内容。
                instance = parsed;
                lastModified = configModified;
                LOGGER.debug("Config loaded from {}", configPath);
                return instance;
            } catch (Exception e) {
                // 解析失败：使用默认值，但保留原文件供用户修复
                LOGGER.error("ohmyworld: failed to parse config {}, using defaults (original file kept)", configPath, e);
                if (e instanceof JsonSyntaxException) {
                    LOGGER.error("ohmyworld: inline \"formula\" strings must escape newlines and quotes; "
                            + "put complex formulas in a .txt file and use \"formula_file\" instead");
                }
                lastModified = configModified;
            }
            instance = new OhMyWorldConfig();
            instance.loadFailed = true;
            return instance;
        }

        instance = new OhMyWorldConfig();
        save();
        return instance;
    }

    /** 解析 formula_file（非 server_mode）：绝对路径原样；相对路径先游戏/服务器目录，再 config/。 */
    static Path resolveFormulaPath(String raw) {
        return resolveFormulaPath(raw, false);
    }

    /**
     * 解析 formula_file：绝对路径原样；相对路径按 server_mode 决定顺序。
     *
     * <p>server_mode 且 formula_file 非空时：ohmyworld/ → 服务器/游戏根目录 →
     * config/。其他情况保持旧顺序：服务器/游戏根目录 → config/。
     */
    static Path resolveFormulaPath(String raw, boolean serverMode) {
        try {
            Path path = Path.of(expandHome(raw));
            if (path.isAbsolute()) return path;
            Platform platform = Platform.get();
            if (platform != null) {
                return resolveFormulaPath(path, platform.gameDir(), platform.configDir(), serverMode);
            }
            return path.toAbsolutePath();
        } catch (InvalidPathException e) {
            LOGGER.error("ohmyworld: invalid formula_file path \"{}\": {}", raw, e.getMessage());
            return null;
        }
    }

    /** 可单测的相对路径查找：优先级见 {@link #resolveFormulaPath(String, boolean)}。 */
    static Path resolveFormulaPath(Path path, Path gameDir, Path configDir, boolean serverMode) {
        Path server = gameDir.resolve(path);
        Path config = configDir.resolve(path);
        if (serverMode) {
            Path ohmyworld = gameDir.resolve("ohmyworld").resolve(path);
            if (Files.isRegularFile(ohmyworld)) return ohmyworld;
        }
        if (Files.isRegularFile(server)) return server;
        if (Files.isRegularFile(config)) return config;
        // 都不存在时返回第一查找位置，错误信息里能看到实际查找路径。
        return serverMode ? gameDir.resolve("ohmyworld").resolve(path) : server;
    }

    /** 支持 ~/xxx（Windows 下也接受 ~\xxx），其余路径交给 Path.of。 */
    private static String expandHome(String raw) {
        if (raw.equals("~")) return System.getProperty("user.home");
        if (raw.startsWith("~/") || raw.startsWith("~\\")) {
            return System.getProperty("user.home") + raw.substring(1);
        }
        return raw;
    }

    /** 读公式 txt：UTF-8、去 BOM、统一 LF；空文件视为错误（通常意味着路径指错或内容没保存）。 */
    static String readFormulaFile(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("formula_file does not exist or is not a regular file: " + path.toAbsolutePath());
        }
        String text = Files.readString(path, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        if (text.isBlank()) {
            throw new IOException("formula_file is empty: " + path.toAbsolutePath());
        }
        return text;
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

    /** 运行中热加载：配置文件或 formula_file 的修改时间变化时重新读取。
     *  由服务器 tick 定期调用；只有 server_mode 下才需要真正应用新公式。 */
    public static boolean reloadIfChanged() {
        try {
            Path path = configPath();
            if (Files.isSymbolicLink(path) || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false;
            long t = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis();
            if (t == lastModified && !formulaSourceChanged()) return false;
            reload();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** formula_file 是否被修改（或刚出现/刚消失）。没有 formula_file 时恒为 false。 */
    private static boolean formulaSourceChanged() {
        Path source = formulaSourcePath;
        if (source == null) return false;
        try {
            if (!Files.exists(source)) return formulaSourceModified != -1;
            return Files.getLastModifiedTime(source).toMillis() != formulaSourceModified;
        } catch (Exception e) {
            return true;
        }
    }
}
