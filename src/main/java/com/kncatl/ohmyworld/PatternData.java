package com.kncatl.ohmyworld;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

public class PatternData {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static List<Object> currentPattern;
    private static String lastRawInput;
    private static boolean active;
    private static boolean pending;

    private static final String DEFAULT_INPUT = "y=0: minecraft:bedrock;y=1..64: (x+z)%2==0 ? minecraft:white_concrete : minecraft:gray_concrete";

    @SuppressWarnings("unchecked")
    public static void set(List<?> pattern, String rawInput) {
        currentPattern = (List<Object>) pattern;
        lastRawInput = stripNewlines(rawInput);
        active = !pattern.isEmpty();
        LOGGER.debug("set() called, active={}, layers={}", active, pattern.size());
    }

    @SuppressWarnings("unchecked")
    public static List<Object> get() {
        if (currentPattern == null || currentPattern.isEmpty()) {
            currentPattern = (List) FormulaParser.parse(DEFAULT_INPUT);
            lastRawInput = DEFAULT_INPUT;
        }
        return currentPattern;
    }

    public static String getRawInput() {
        get();
        return lastRawInput;
    }

    public static boolean isActive() { return active; }
    public static void clearActive() { active = false; }

    public static void setPending() {
        pending = true;
        LOGGER.debug("setPending() called");
    }

    public static boolean isPending() { return pending; }

    private static Path markerPath(ServerLevel level) {
        return level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("ohmyworld_marker.txt");
    }

    public static void markActive(ServerLevel level) {
        active = true;
        try {
            Path marker = markerPath(level);
            LOGGER.debug("markActive: path={}, exists={}", marker, Files.exists(marker));
            if (!Files.exists(marker)) {
                Files.createDirectories(marker.getParent());
                String content = getRawInput();
                LOGGER.debug("markActive: writing content='{}'", content);
                Files.writeString(marker, content);
                LOGGER.debug("markActive: wrote OK, exists={}", Files.exists(marker));
            }
        } catch (Exception e) {
            LOGGER.error("markActive failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    public static boolean restoreFromMarker(ServerLevel level) {
        try {
            Path marker = markerPath(level);
            LOGGER.debug("restoreFromMarker: path={}, exists={}", marker, Files.exists(marker));
            if (Files.exists(marker)) {
                String raw = Files.readString(marker);
                LOGGER.debug("restoreFromMarker: read content='{}'", raw);
                if (raw != null && !raw.isBlank()) {
                    currentPattern = (List) FormulaParser.parse(raw);
                    lastRawInput = raw;
                    active = true;
                    pending = false;
                    LOGGER.debug("restoreFromMarker: restored OK");
                    return true;
                }
            }
        } catch (Exception e) {
            LOGGER.error("restoreFromMarker failed", e);
        }
        return false;
    }

    public static void fillChunk(ChunkAccess chunk, List<Object> layers) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        Heightmap h0 = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap h1 = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        int cx = chunk.getPos().getMinBlockX();
        int cz = chunk.getPos().getMinBlockZ();

        for (Object obj : layers) {
            if (obj instanceof FormulaLayerDef f) {
                for (int y = f.yStart(); y <= f.yEnd(); y++) {
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            BlockState st = f.getBlock(cx + x, cz + z, y);
                            chunk.setBlockState(pos.set(x, y, z), st, false);
                            h0.update(x, y, z, st);
                            h1.update(x, y, z, st);
                        }
                    }
                }
            } else if (obj instanceof CyclicLayerDef c) {
                for (int y = c.yStart(); y <= c.yEnd(); y++) {
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            BlockState st = c.getBlock(cx + x, cz + z, y);
                            chunk.setBlockState(pos.set(x, y, z), st, false);
                            h0.update(x, y, z, st);
                            h1.update(x, y, z, st);
                        }
                    }
                }
            }
        }
    }

    private static String stripNewlines(String s) {
        return s.replace("\r", "").replace("\n", "");
    }
}
