package com.kncatl.ohmyworld.compat;

import net.minecraft.core.registries.BuiltInRegistries;
//? >=1.21.11 {
import net.minecraft.resources.Identifier;
//?} else {
import net.minecraft.resources.ResourceLocation;
//?}
import net.minecraft.world.level.block.Block;

/**
 * 注册表查询的版本差异收敛层。
 *
 * <p>1.21.11 起 {@code Registry.get(...)} 返回 {@code Optional<Holder>} 而非直接
 * 返回值，同时 {@code ResourceLocation} 改名为 {@code Identifier}。
 * 业务代码统一走这里。
 */
public final class RegistryLookup {

    private RegistryLookup() {}

    /** 按字符串 ID 查方块；格式非法或方块不存在时返回 {@code null}。 */
    public static Block blockById(String blockId) {
        //? >=1.21.11 {
        Identifier loc = Identifier.tryParse(blockId);
        return loc == null ? null : BuiltInRegistries.BLOCK.get(loc).map(h -> h.value()).orElse(null);
        //?} else {
        ResourceLocation loc = ResourceLocation.tryParse(blockId);
        return loc == null ? null : BuiltInRegistries.BLOCK.get(loc);
        //?}
    }
}
