package com.kncatl.ohmyworld;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
//? >=1.21.11 {
import net.minecraft.resources.Identifier;
//?} else {
import net.minecraft.resources.ResourceLocation;
//?}
import net.minecraft.world.level.levelgen.presets.WorldPreset;

public final class PresetKeys {

    /** ohmyworld:flat_plus 预设键（加载器通用，服务端安全，无客户端依赖） */
    public static final ResourceKey<WorldPreset> OUR_KEY = ResourceKey.create(Registries.WORLD_PRESET,
            //? >=1.21.11 {
            Identifier.fromNamespaceAndPath("ohmyworld", "flat_plus"));
            //?} else {
            ResourceLocation.fromNamespaceAndPath("ohmyworld", "flat_plus"));
            //?}

    private PresetKeys() {}
}
