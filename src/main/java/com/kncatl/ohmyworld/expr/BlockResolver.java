package com.kncatl.ohmyworld.expr;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import com.kncatl.ohmyworld.compat.ColoredBlocks;
import com.kncatl.ohmyworld.compat.RegistryLookup;

public class BlockResolver {

    /** 方块 ID → BlockState 缓存，避免每个方块位置重复注册表查询。 */
    private static final Map<String, BlockState> CACHE = new ConcurrentHashMap<>();

    public static BlockState resolve(String blockId) {
        BlockState cached = CACHE.get(blockId);
        if (cached != null) return cached;
        Block b = find(blockId);
        BlockState st = b != null ? b.defaultBlockState() : Blocks.AIR.defaultBlockState();
        CACHE.putIfAbsent(blockId, st);
        return st;
    }

    public static boolean exists(String blockId) {
        return find(blockId) != null;
    }

    /**
     * 常用方块走硬编码快路径（避免注册表查询），其余交给
     * {@link RegistryLookup}（其内部处理版本差异）。
     */
    private static Block find(String blockId) {
        return switch (blockId) {
            case "minecraft:stone" -> Blocks.STONE; case "minecraft:dirt" -> Blocks.DIRT;
            case "minecraft:grass_block" -> Blocks.GRASS_BLOCK; case "minecraft:bedrock" -> Blocks.BEDROCK;
            case "minecraft:white_concrete" -> ColoredBlocks.concrete(DyeColor.WHITE);
            case "minecraft:gray_concrete" -> ColoredBlocks.concrete(DyeColor.GRAY);
            case "minecraft:black_concrete" -> ColoredBlocks.concrete(DyeColor.BLACK);
            case "minecraft:red_concrete" -> ColoredBlocks.concrete(DyeColor.RED);
            case "minecraft:blue_concrete" -> ColoredBlocks.concrete(DyeColor.BLUE);
            case "minecraft:yellow_concrete" -> ColoredBlocks.concrete(DyeColor.YELLOW);
            case "minecraft:green_concrete" -> ColoredBlocks.concrete(DyeColor.GREEN);
            case "minecraft:orange_concrete" -> ColoredBlocks.concrete(DyeColor.ORANGE);
            case "minecraft:purple_concrete" -> ColoredBlocks.concrete(DyeColor.PURPLE);
            case "minecraft:light_gray_concrete" -> ColoredBlocks.concrete(DyeColor.LIGHT_GRAY);
            case "minecraft:light_blue_concrete" -> ColoredBlocks.concrete(DyeColor.LIGHT_BLUE);
            case "minecraft:magenta_concrete" -> ColoredBlocks.concrete(DyeColor.MAGENTA);
            case "minecraft:lime_concrete" -> ColoredBlocks.concrete(DyeColor.LIME);
            case "minecraft:pink_concrete" -> ColoredBlocks.concrete(DyeColor.PINK);
            case "minecraft:cyan_concrete" -> ColoredBlocks.concrete(DyeColor.CYAN);
            case "minecraft:brown_concrete" -> ColoredBlocks.concrete(DyeColor.BROWN);
            case "minecraft:air" -> Blocks.AIR; case "minecraft:cobblestone" -> Blocks.COBBLESTONE;
            case "minecraft:oak_planks" -> Blocks.OAK_PLANKS; case "minecraft:glass" -> Blocks.GLASS;
            case "minecraft:obsidian" -> Blocks.OBSIDIAN; case "minecraft:sand" -> Blocks.SAND;
            case "minecraft:gravel" -> Blocks.GRAVEL; case "minecraft:water" -> Blocks.WATER; case "minecraft:lava" -> Blocks.LAVA;
            default -> RegistryLookup.blockById(blockId);
        };
    }
}
