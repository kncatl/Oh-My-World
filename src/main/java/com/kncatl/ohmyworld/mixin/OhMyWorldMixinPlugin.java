package com.kncatl.ohmyworld.mixin;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * 按版本跳过不适用的 mixin。
 *
 * <p>26.3 起 {@code net.minecraft.world.level.levelgen.carver.WorldCarver} 从抽象类
 * 变成接口，而 {@code MixinWorldCarver}（注入 &lt;26.3 的 {@code carveBlock}）无法
 * 以接口为 mixin 目标——直接声明会在 26.3 上让 mixin 准备阶段报错、服务端起不来。
 * 版本常量由 Stonecutter 在编译期固化（不依赖运行期类加载探测，避免 prepare 阶段
 * 的类加载副作用）：26.3 跳过 {@code MixinWorldCarver}，水体保护由
 * {@code MixinCarvingMask} 承担（见该类注释）。
 */
public class OhMyWorldMixinPlugin implements IMixinConfigPlugin {

    /** 26.3 起 WorldCarver 是接口，不能作为类 mixin 目标。 */
    //? <26.3 {
    private static final boolean WORLD_CARVER_IS_CLASS = true;
    //?} else {
    private static final boolean WORLD_CARVER_IS_CLASS = false;
    //?}

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith(".MixinWorldCarver")) {
            return WORLD_CARVER_IS_CLASS;
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                         IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                          IMixinInfo mixinInfo) {
    }
}
