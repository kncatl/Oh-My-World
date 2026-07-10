package com.kncatl.flatpattern;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;

public class PatternFlatSource extends FlatLevelSource {

    public static final MapCodec<PatternFlatSource> CODEC = RecordCodecBuilder.mapCodec(
            instance -> instance.group(
                    FlatLevelGeneratorSettings.CODEC.fieldOf("settings").forGetter(PatternFlatSource::settings)
            ).apply(instance, instance.stable(PatternFlatSource::new))
    );

    public PatternFlatSource(FlatLevelGeneratorSettings settings) {
        super(settings);
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState,
                                                         net.minecraft.world.level.StructureManager structureManager,
                                                         ChunkAccess chunk) {
        List<Object> layers = PatternData.get();
        if (layers == null || layers.isEmpty()) {
            return super.fillFromNoise(blender, randomState, structureManager, chunk);
        }

        PatternData.fillChunk(chunk, layers);
        return CompletableFuture.completedFuture(chunk);
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random) {
        List<Object> layers = PatternData.get();
        int top = level.getMinBuildHeight();
        for (Object obj : layers) {
            int yEnd;
            if (obj instanceof FormulaLayerDef f) yEnd = f.yEnd();
            else if (obj instanceof CyclicLayerDef c) yEnd = c.yEnd();
            else continue;
            if (yEnd >= top) top = yEnd + 1;
        }
        return top;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor height, RandomState random) {
        List<Object> layers = PatternData.get();
        int minY = height.getMinBuildHeight();
        int total = height.getHeight();
        BlockState[] column = new BlockState[total];
        Arrays.fill(column, Blocks.AIR.defaultBlockState());

        for (Object obj : layers) {
            if (obj instanceof FormulaLayerDef f) {
                for (int y = f.yStart(); y <= f.yEnd(); y++) {
                    int idx = y - minY;
                    if (idx >= 0 && idx < total) column[idx] = f.getBlock(x, z, y);
                }
            } else if (obj instanceof CyclicLayerDef c) {
                for (int y = c.yStart(); y <= c.yEnd(); y++) {
                    int idx = y - minY;
                    if (idx >= 0 && idx < total) column[idx] = c.getBlock(x, z, y);
                }
            }
        }
        return new NoiseColumn(minY, column);
    }
}
