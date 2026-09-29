package com.kncatl.ohmyworld.compat;

import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * 彩色方块家族的版本差异收敛层。
 *
 * <p>26.3 起 MC 把每个颜色族合并成一个 {@code ColorCollection}
 * （{@code Blocks.CONCRETE}），按 {@link DyeColor} 取块；更早版本是
 * 16 个独立常量（{@code Blocks.WHITE_CONCRETE} 等）。
 */
public final class ColoredBlocks {

    private ColoredBlocks() {}

    /** 该颜色的混凝土方块。 */
    public static Block concrete(DyeColor color) {
        //? >=26.3 {
        return Blocks.CONCRETE.pick(color);
        //?} else {
        return switch (color) {
            case WHITE -> Blocks.WHITE_CONCRETE;
            case ORANGE -> Blocks.ORANGE_CONCRETE;
            case MAGENTA -> Blocks.MAGENTA_CONCRETE;
            case LIGHT_BLUE -> Blocks.LIGHT_BLUE_CONCRETE;
            case YELLOW -> Blocks.YELLOW_CONCRETE;
            case LIME -> Blocks.LIME_CONCRETE;
            case PINK -> Blocks.PINK_CONCRETE;
            case GRAY -> Blocks.GRAY_CONCRETE;
            case LIGHT_GRAY -> Blocks.LIGHT_GRAY_CONCRETE;
            case CYAN -> Blocks.CYAN_CONCRETE;
            case PURPLE -> Blocks.PURPLE_CONCRETE;
            case BLUE -> Blocks.BLUE_CONCRETE;
            case BROWN -> Blocks.BROWN_CONCRETE;
            case GREEN -> Blocks.GREEN_CONCRETE;
            case RED -> Blocks.RED_CONCRETE;
            case BLACK -> Blocks.BLACK_CONCRETE;
        };
        //?}
    }
}
