package com.kncatl.ohmyworld;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * {@code [carvers:vanilla-ew]}（except water）的支撑：放行原版雕刻，但**跳过流体
 * 方块本身**——水与岩浆都不会被替换成空气/空腔（海洋、河流、湖泊里不再出现被掏
 * 出来的洞穴与峡谷；下界/公式地形里的岩浆池也不会被掏空）；流体下方的固体照常雕刻。
 *
 * <p>之所以把岩浆也纳入：下界 carver 会把公式岩浆海/岩浆池当作可雕刻方块挖掉，
 * 留下悬空的岩浆或空腔；语义与水一致——只保护流体本身，流体下方/周围岩石照常挖。
 *
 * <p>工作方式：在放行雕刻之前调用 {@link #begin(ChunkAccess)} 记下当前区块，
 * 雕刻逐点调用 {@link #skipFluid} / {@link #skipFluidLocal}——该点当前是水或岩浆
 * 时拒绝这次写入；{@link #end()} 在雕刻结束后清除。未 begin 时所有判定返回 false，
 * 原版与其他维度完全不受影响。
 *
 * <p>线程本地：雕刻在同一条生成线程上完成；begin/end 由调用方负责配对
 * （异常路径下残留的旧值会因区块对象不同而自动失效，并在下次 begin 时替换）。
 */
public final class CarverFluidGuard {

    private static final ThreadLocal<Guard> ACTIVE = new ThreadLocal<>();

    private record Guard(ChunkAccess chunk, BlockPos.MutableBlockPos pos) {}

    private CarverFluidGuard() {}

    /** 开始对当前区块启用「跳过流体」保护。 */
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

    /** 该点是否应跳过这次雕刻（是水或岩浆）。{@code pos} 为世界坐标（≤26.1.2 的 carveBlock 用）。 */
    public static boolean skipFluid(ChunkAccess chunk, BlockPos pos) {
        Guard guard = ACTIVE.get();
        if (guard == null || guard.chunk() != chunk) return false;
        return isProtectedFluid(chunk.getBlockState(pos));
    }

    /** 该点是否应跳过这次雕刻（是水或岩浆）。{@code x/z} 为区块内坐标 0..15（≥26.3 的 CarvingMask 用）。 */
    public static boolean skipFluidLocal(int x, int y, int z) {
        Guard guard = ACTIVE.get();
        if (guard == null) return false;
        ChunkAccess chunk = guard.chunk();
        BlockState state = chunk.getBlockState(guard.pos().set(
                chunk.getPos().getMinBlockX() + (x & 15), y,
                chunk.getPos().getMinBlockZ() + (z & 15)));
        return isProtectedFluid(state);
    }

    private static boolean isProtectedFluid(BlockState state) {
        var fluid = state.getFluidState();
        return fluid.is(FluidTags.WATER) || fluid.is(FluidTags.LAVA);
    }
}
