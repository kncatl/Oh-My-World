package com.kncatl.ohmyworld;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * {@code [carvers:vanilla-ew]}（except water）的支撑：放行原版雕刻，但**跳过水
 * 方块本身**——水不会被替换成空气/空腔（海洋、河流、湖泊里不再出现被掏出来的
 * 洞穴与峡谷）；水下的固体（海床及其下方）与无水陆地照常雕刻。
 *
 * <p>工作方式：在放行雕刻之前调用 {@link #begin(ChunkAccess)} 记下当前区块，
 * 雕刻逐点调用 {@link #skipWater} / {@link #skipWaterLocal}——该点当前是水
 * （水方块或含水流体）时拒绝这次写入；{@link #end()} 在雕刻结束后清除。
 * 未 begin 时所有判定返回 false，原版与其他维度完全不受影响。
 *
 * <p>线程本地：雕刻在同一条生成线程上完成；begin/end 由调用方负责配对
 * （异常路径下残留的旧值会因区块对象不同而自动失效，并在下次 begin 时替换）。
 */
public final class CarverWaterGuard {

    private static final ThreadLocal<Guard> ACTIVE = new ThreadLocal<>();

    private record Guard(ChunkAccess chunk, BlockPos.MutableBlockPos pos) {}

    private CarverWaterGuard() {}

    /** 开始对当前区块启用「跳过水」保护。 */
    public static void begin(ChunkAccess chunk) {
        if (chunk == null) {
            ACTIVE.remove();
            return;
        }
        Guard guard = ACTIVE.get();
        if (guard == null || guard.chunk() != chunk) {
            ACTIVE.set(new Guard(chunk, new BlockPos.MutableBlockPos()));
        }
    }

    /** 结束保护；未启用时无副作用。 */
    public static void end() {
        ACTIVE.remove();
    }

    /** 该点是否应跳过这次雕刻（是水方块）。{@code pos} 为世界坐标（≤26.1.2 的 carveBlock 用）。 */
    public static boolean skipWater(ChunkAccess chunk, BlockPos pos) {
        Guard guard = ACTIVE.get();
        if (guard == null || guard.chunk() != chunk) return false;
        return isWater(chunk.getBlockState(pos));
    }

    /** 该点是否应跳过这次雕刻（是水方块）。{@code x/z} 为区块内坐标 0..15（≥26.3 的 CarvingMask 用）。 */
    public static boolean skipWaterLocal(int x, int y, int z) {
        Guard guard = ACTIVE.get();
        if (guard == null) return false;
        ChunkAccess chunk = guard.chunk();
        BlockState state = chunk.getBlockState(guard.pos().set(
                chunk.getPos().getMinBlockX() + (x & 15), y,
                chunk.getPos().getMinBlockZ() + (z & 15)));
        return isWater(state);
    }

    private static boolean isWater(BlockState state) {
        return state.getFluidState().is(FluidTags.WATER);
    }
}
