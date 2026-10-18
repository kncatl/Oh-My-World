package com.kncatl.ohmyworld.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * 区块写入的版本差异收敛层。
 *
 * <p>1.21.5 起 {@code ChunkAccess.setBlockState} 不再接收显式的 flags 参数；
 * 原先的 {@code false}（不加标志）语义由单参重载承担。
 */
public final class ChunkWrites {

    private ChunkWrites() {}

    /**
     * 无标志写入方块。
     *
     * <p>与原版 FlatLevelSource 一致：不加标志可避免生成期触发额外的光照与方块更新开销。
     */
    public static void setBlock(ChunkAccess chunk, BlockPos pos, BlockState state) {
        //? >=1.21.5 {
        chunk.setBlockState(pos, state);
        //?} else {
        chunk.setBlockState(pos, state, false);
        //?}
    }

    /** 生成期读取 section 方块（与 {@link #setSectionBlock} 配对使用）。 */
    public static BlockState getSectionBlock(LevelChunkSection section, int x, int y, int z) {
        return section.getBlockState(x, y, z);
    }

    /**
     * 生成期直接写 section（无锁调色板）：
     * 绕过 {@code ChunkAccess.setBlockState} 的边界/分节/光照检查与自动高度图更新，
     * 由调用方在整块写完后按列维护高度图（2026-10 性能优化）。
     */
    public static void setSectionBlock(LevelChunkSection section, int x, int y, int z, BlockState state) {
        section.setBlockState(x, y, z, state, false);
    }

    /**
     * 标记该坐标参与"生成后处理"（新放置/移除的水、岩浆等流体）。
     *
     * <p>26.3 的方法名改成了 {@code markPosForPostProcessing}（首字母大写）。
     */
    public static void markForPostProcessing(ChunkAccess chunk, BlockPos pos) {
        //? >=26.3 {
        chunk.markPosForPostProcessing(pos);
        //?} else {
        chunk.markPosForPostprocessing(pos);
        //?}
    }
}
