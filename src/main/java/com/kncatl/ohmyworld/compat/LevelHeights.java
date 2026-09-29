package com.kncatl.ohmyworld.compat;

import net.minecraft.world.level.LevelHeightAccessor;

/**
 * 世界高度访问的版本差异收敛层。
 *
 * <p>实测边界：{@code getMinBuildHeight/getMaxBuildHeight} 在 <b>1.21.2</b> 已不可用，
 * 1.21.2 起必须用 {@code getMinY/getMaxY}（1.21.1 只有旧名字）。
 * 业务代码统一走这里，不要各自写条件分支。
 *
 * <p>若将来接入 1.21.2，需要实测确认 1.21.2 属于哪一侧，再调整门限。
 */
public final class LevelHeights {

    private LevelHeights() {}

    /** 世界（或区块）的最低可建筑 Y。 */
    public static int minY(LevelHeightAccessor level) {
        //? >=1.21.2 {
        return level.getMinY();
        //?} else {
        return level.getMinBuildHeight();
        //?}
    }

    /** 世界（或区块）的最高可建筑 Y（不含）。 */
    public static int maxY(LevelHeightAccessor level) {
        //? >=1.21.2 {
        return level.getMaxY();
        //?} else {
        return level.getMaxBuildHeight();
        //?}
    }
}
