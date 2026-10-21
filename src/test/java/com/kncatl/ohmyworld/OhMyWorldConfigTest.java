package com.kncatl.ohmyworld;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.kncatl.ohmyworld.platform.LevelLoadCallback;
import com.kncatl.ohmyworld.platform.Platform;
import com.kncatl.ohmyworld.platform.ServerTickCallback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OhMyWorldConfigTest {

    @TempDir
    Path tempDir;

    private Platform originalPlatform;

    @BeforeEach
    void rememberPlatform() {
        originalPlatform = Platform.get();
    }

    @AfterEach
    void restorePlatform() {
        Platform.set(originalPlatform);
    }

    @Test
    void readsUtf8BomAndNormalizesLineEndings() throws Exception {
        Path file = tempDir.resolve("formula.txt");
        Files.writeString(file, "\uFEFF{overworld=[carvers:none]\r\n  y=..: minecraft:stone;\r\n}\r\n",
                StandardCharsets.UTF_8);
        assertEquals("{overworld=[carvers:none]\n  y=..: minecraft:stone;\n}\n",
                OhMyWorldConfig.readFormulaFile(file));
    }

    @Test
    void rejectsEmptyFormulaFile() throws Exception {
        Path file = tempDir.resolve("empty.txt");
        Files.writeString(file, " \n\t", StandardCharsets.UTF_8);
        IOException error = assertThrows(IOException.class, () -> OhMyWorldConfig.readFormulaFile(file));
        assertTrue(error.getMessage().contains("empty"));
    }

    @Test
    void rejectsMissingFormulaFile() {
        Path file = tempDir.resolve("missing.txt");
        assertThrows(IOException.class, () -> OhMyWorldConfig.readFormulaFile(file));
    }

    @Test
    void absolutePathIsUsedAsIs() {
        Path file = tempDir.resolve("absolute.txt");
        assertEquals(file, OhMyWorldConfig.resolveFormulaPath(file.toString()));
    }

    @Test
    void serverModePrefersOhmyworldThenServerRootThenConfig() throws Exception {
        Path game = tempDir.resolve("server");
        Path config = game.resolve("config");
        Path ohmyworld = game.resolve("ohmyworld");
        Files.createDirectories(ohmyworld);
        Files.createDirectories(config);
        Path relative = Path.of("formula.txt");
        Path rootFile = game.resolve(relative);
        Path configFile = config.resolve(relative);
        Path ohmyFile = ohmyworld.resolve(relative);

        Files.writeString(rootFile, "root", StandardCharsets.UTF_8);
        Files.writeString(configFile, "config", StandardCharsets.UTF_8);
        Files.writeString(ohmyFile, "ohmyworld", StandardCharsets.UTF_8);
        assertEquals(ohmyFile, OhMyWorldConfig.resolveFormulaPath(relative, game, config, true));

        Files.delete(ohmyFile);
        assertEquals(rootFile, OhMyWorldConfig.resolveFormulaPath(relative, game, config, true));

        Files.delete(rootFile);
        assertEquals(configFile, OhMyWorldConfig.resolveFormulaPath(relative, game, config, true));
    }

    @Test
    void nonServerModeKeepsRootThenConfigOrder() throws Exception {
        Path game = tempDir.resolve("client");
        Path config = game.resolve("config");
        Path ohmyworld = game.resolve("ohmyworld");
        Files.createDirectories(ohmyworld);
        Files.createDirectories(config);
        Path relative = Path.of("formula.txt");
        Path rootFile = game.resolve(relative);
        Path ohmyFile = ohmyworld.resolve(relative);
        Files.writeString(rootFile, "root", StandardCharsets.UTF_8);
        Files.writeString(ohmyFile, "ohmyworld", StandardCharsets.UTF_8);

        assertEquals(rootFile, OhMyWorldConfig.resolveFormulaPath(relative, game, config, false));
    }

    @Test
    void malformedJsonIsFlaggedForMarkerFallback() throws Exception {
        Path game = tempDir.resolve("server");
        Path config = game.resolve("config");
        Files.createDirectories(config);
        Files.writeString(config.resolve("ohmyworld.json"), "{ this is not json",
                StandardCharsets.UTF_8);
        Platform.set(new TestPlatform(game, config));

        OhMyWorldConfig loaded = OhMyWorldConfig.reload();
        assertTrue(loaded.loadFailed());
        assertTrue(loaded.jsonSyntaxFailed());
        assertSame(loaded, OhMyWorldConfig.load(), "失败结果必须缓存，避免重复读损坏配置");
    }

    @Test
    void nonServerModeDoesNotReadFormulaFile() throws Exception {
        Path game = tempDir.resolve("server");
        Path config = game.resolve("config");
        Files.createDirectories(config);
        Files.writeString(config.resolve("ohmyworld.json"),
                "{\"server_mode\":false,\"formula_file\":\"missing.txt\"}",
                StandardCharsets.UTF_8);
        Platform.set(new TestPlatform(game, config));

        OhMyWorldConfig loaded = OhMyWorldConfig.reload();
        assertFalse(loaded.loadFailed());
        assertEquals(PatternData.DEFAULT_INPUT, loaded.formula());
    }

    @Test
    void serverModeStillFailsClosedWhenFormulaFileIsMissing() throws Exception {
        Path game = tempDir.resolve("server");
        Path config = game.resolve("config");
        Files.createDirectories(config);
        Files.writeString(config.resolve("ohmyworld.json"),
                "{\"server_mode\":true,\"formula_file\":\"missing.txt\"}",
                StandardCharsets.UTF_8);
        Platform.set(new TestPlatform(game, config));

        assertTrue(OhMyWorldConfig.reload().loadFailed());
    }

    private record TestPlatform(Path gameDir, Path configDir) implements Platform {
        @Override public void onLevelLoad(LevelLoadCallback callback) {}
        @Override public void onServerTick(ServerTickCallback callback) {}
    }
}
