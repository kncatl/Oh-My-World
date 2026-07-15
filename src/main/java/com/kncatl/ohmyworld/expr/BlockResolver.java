package com.kncatl.ohmyworld.expr;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public class BlockResolver {
    public static BlockState resolve(String blockId) {
        Block b = switch (blockId) {
            case "minecraft:stone" -> Blocks.STONE; case "minecraft:dirt" -> Blocks.DIRT;
            case "minecraft:grass_block" -> Blocks.GRASS_BLOCK; case "minecraft:bedrock" -> Blocks.BEDROCK;
            case "minecraft:white_concrete" -> Blocks.WHITE_CONCRETE; case "minecraft:gray_concrete" -> Blocks.GRAY_CONCRETE;
            case "minecraft:black_concrete" -> Blocks.BLACK_CONCRETE; case "minecraft:red_concrete" -> Blocks.RED_CONCRETE;
            case "minecraft:blue_concrete" -> Blocks.BLUE_CONCRETE; case "minecraft:yellow_concrete" -> Blocks.YELLOW_CONCRETE;
            case "minecraft:green_concrete" -> Blocks.GREEN_CONCRETE; case "minecraft:orange_concrete" -> Blocks.ORANGE_CONCRETE;
            case "minecraft:purple_concrete" -> Blocks.PURPLE_CONCRETE; case "minecraft:light_gray_concrete" -> Blocks.LIGHT_GRAY_CONCRETE;
            case "minecraft:light_blue_concrete" -> Blocks.LIGHT_BLUE_CONCRETE; case "minecraft:magenta_concrete" -> Blocks.MAGENTA_CONCRETE;
            case "minecraft:lime_concrete" -> Blocks.LIME_CONCRETE; case "minecraft:pink_concrete" -> Blocks.PINK_CONCRETE;
            case "minecraft:cyan_concrete" -> Blocks.CYAN_CONCRETE; case "minecraft:brown_concrete" -> Blocks.BROWN_CONCRETE;
            case "minecraft:air" -> Blocks.AIR; case "minecraft:cobblestone" -> Blocks.COBBLESTONE;
            case "minecraft:oak_planks" -> Blocks.OAK_PLANKS; case "minecraft:glass" -> Blocks.GLASS;
            case "minecraft:obsidian" -> Blocks.OBSIDIAN; case "minecraft:sand" -> Blocks.SAND;
            case "minecraft:gravel" -> Blocks.GRAVEL; case "minecraft:water" -> Blocks.WATER; case "minecraft:lava" -> Blocks.LAVA;
            default -> { ResourceLocation loc = ResourceLocation.tryParse(blockId); yield loc != null ? BuiltInRegistries.BLOCK.get(loc) : null; }
        };
        return b != null ? b.defaultBlockState() : Blocks.AIR.defaultBlockState();
    }
}
