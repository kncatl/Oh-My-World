package com.kncatl.ohmyworld;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;

@EventBusSubscriber(modid = "ohmyworld")
public class WorldLoadHandler {
    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel sl)) return;
        if (sl.dimension() != Level.OVERWORLD) return;
        OhMyWorldConfig config = OhMyWorldConfig.load();
        if (config.serverMode()) {
            PatternData.set(FormulaParser.parse(config.formula()), config.formula());
        } else if (!PatternData.restoreFromMarker(sl)) {
            if (PatternData.isPending()) PatternData.markActive(sl);
            else PatternData.clearActive();
        }
        if (PatternData.isActive()) {
            ChunkGenerator gen = sl.getChunkSource().getGenerator();
            if (!(gen instanceof PatternFlatSource) && gen instanceof FlatLevelSource flatSource) {
                setGenerator(sl, new PatternFlatSource(flatSource.settings()));
            }
        }
    }
    private static void setGenerator(ServerLevel level, ChunkGenerator newGen) {
        if (!(level.getChunkSource() instanceof ServerChunkCache cache)) return;
        try { var f = ServerChunkCache.class.getDeclaredField("generator"); f.setAccessible(true); f.set(cache, newGen); } catch (Exception ignored) {}
    }
}
