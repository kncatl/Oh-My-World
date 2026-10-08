package com.kncatl.ohmyworld.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

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
