package com.kncatl.ohmyworld.mixin;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.chunk.ChunkGenerator;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@code ChunkGenerator} 的三个私有字段访问器（{@code [biome:...]} 指令运行时替换用）：
 * <ul>
 *   <li>{@code biomeSource}（{@code protected final}）：换群系源；</li>
 *   <li>{@code generationSettingsGetter}（{@code private final}）：超平坦基座的"配置群系
 *       裁剪"放行——换群系后要让所有群系返回原版生成设置，装饰才跟着群系走；</li>
 *   <li>{@code featuresPerStep}（{@code private final} 的 memoize 供应商）：装饰特性索引表，
 *       构造时按"当时的群系源 + 生成设置"建立；换群系后必须重建，否则特性查不到索引。</li>
 * </ul>
 * 字段名与类型 1.21.1–26.3 一致（构造 lambda 形状已核对：
 * {@code FeatureSorter.buildFeaturesPerStep(List.copyOf(source.possibleBiomes()), biome -> getter.apply(biome).features(), true)}）。
 */
@Mixin(ChunkGenerator.class)
public interface ChunkGeneratorBiomeSourceAccessor {

    @Mutable
    @Accessor("biomeSource")
    void ohmyworld$setBiomeSource(BiomeSource source);

    @Accessor("generationSettingsGetter")
    Function<Holder<Biome>, BiomeGenerationSettings> ohmyworld$getGenerationSettingsGetter();

    @Mutable
    @Accessor("generationSettingsGetter")
    void ohmyworld$setGenerationSettingsGetter(Function<Holder<Biome>, BiomeGenerationSettings> getter);

    @Accessor("featuresPerStep")
    Supplier<List<FeatureSorter.StepFeatureData>> ohmyworld$getFeaturesPerStep();

    @Mutable
    @Accessor("featuresPerStep")
    void ohmyworld$setFeaturesPerStep(Supplier<List<FeatureSorter.StepFeatureData>> featuresPerStep);
}
