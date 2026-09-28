package com.kncatl.ohmyworld;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

import com.kncatl.ohmyworld.compat.ResourceIds;

public final class PresetKeys {

    /** ohmyworld:flat_plus 预设键（加载器通用，服务端安全，无客户端依赖） */
    public static final ResourceKey<WorldPreset> OUR_KEY =
            ResourceKey.create(Registries.WORLD_PRESET, ResourceIds.of("ohmyworld", "flat_plus"));

    private PresetKeys() {}
}
