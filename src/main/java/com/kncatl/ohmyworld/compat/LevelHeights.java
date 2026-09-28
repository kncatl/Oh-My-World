package com.kncatl.ohmyworld.compat;

import net.minecraft.world.level.LevelHeightAccessor;

/**
 * 世界高度访问的版本差异收敛层。
 *
 * <p>1.21.5 起 {@code getMinBuildHeight/getMaxBuildHeight} 改名为
 * {@code getMinY/getMaxY}。业务代码统一走这里，不要各自写条件分支。
 */
public final class LevelHeights {

    private LevelHeights() {}

    /** 世界（或区块）的最低可建筑 Y。 */
    public static int minY(LevelHeightAccessor level) {
        //? >=1.21.5 {
        return level.getMinY();
        //?} else {
        return level.getMinBuildHeight();
        //?}
    }

    /** 世界（或区块）的最高可建筑 Y（不含）。 */
    public static int maxY(LevelHeightAccessor level) {
        //? >=1.21.5 {
        return level.getMaxY();
        //?} else {
        return level.getMaxBuildHeight();
        //?}
    }
}
