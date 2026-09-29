package com.kncatl.ohmyworld.expr;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

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
     *
     * <p>混凝土 16 色刻意不进白名单：它们的常量在 26.2 被 MC 合并成
     * {@code ColorCollection}（26.1.2 及更早又只有常量），没有任何编译期写法能同时
     * 覆盖 26.1.2–26.3 的整个区间；注册表名称跨版本稳定，交给 RegistryLookup 即可
     * （结果有 CACHE，重复解析不产生额外查询）。
     */
    private static Block find(String blockId) {
        return switch (blockId) {
            case "minecraft:stone" -> Blocks.STONE; case "minecraft:dirt" -> Blocks.DIRT;
            case "minecraft:grass_block" -> Blocks.GRASS_BLOCK; case "minecraft:bedrock" -> Blocks.BEDROCK;
            case "minecraft:air" -> Blocks.AIR; case "minecraft:cobblestone" -> Blocks.COBBLESTONE;
            case "minecraft:oak_planks" -> Blocks.OAK_PLANKS; case "minecraft:glass" -> Blocks.GLASS;
            case "minecraft:obsidian" -> Blocks.OBSIDIAN; case "minecraft:sand" -> Blocks.SAND;
            case "minecraft:gravel" -> Blocks.GRAVEL; case "minecraft:water" -> Blocks.WATER; case "minecraft:lava" -> Blocks.LAVA;
            default -> RegistryLookup.blockById(blockId);
        };
    }
}
