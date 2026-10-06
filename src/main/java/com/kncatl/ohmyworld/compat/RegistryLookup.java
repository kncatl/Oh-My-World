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
 * <p>这里是<b>两个相互独立</b>的版本差异，必须分开判定：
 * <ul>
 *   <li>资源 ID 类型：1.21.11 起 {@code ResourceLocation} 改名为 {@code Identifier}
 *       （1.21.3 仍然叫 ResourceLocation）；</li>
 *   <li>查询返回类型：<b>1.21.2</b> 起 {@code Registry.get(...)} 返回
 *       {@code Optional<Holder.Reference<T>>}；1.21.1 只有直接返回值的老签名。</li>
 * </ul>
 * 不要把两者合并成同一个门限。
 */
public final class RegistryLookup {

    private RegistryLookup() {}

    /** 按字符串 ID 查方块；格式非法或方块不存在时返回 {@code null}。 */
    public static Block blockById(String blockId) {
        //? >=1.21.11 {
        Identifier loc = Identifier.tryParse(blockId);
        //?} else {
        ResourceLocation loc = ResourceLocation.tryParse(blockId);
        //?}
        if (loc == null) return null;
        //? >=1.21.2 {
        return BuiltInRegistries.BLOCK.get(loc).map(h -> h.value()).orElse(null);
        //?} else {
        // 1.21.1 的方块注册表是 DefaultedRegistry：get() 对未知 ID 会「回落成空气」
        // 而不是返回 null（getOptional 同理），直接返回会把未知方块当成合法。
        // 因此先判存在性，再取值。
        return BuiltInRegistries.BLOCK.containsKey(loc) ? BuiltInRegistries.BLOCK.get(loc) : null;
        //?}
    }
}
