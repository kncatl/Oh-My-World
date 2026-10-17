package com.kncatl.ohmyworld.compat;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.RandomState;

import com.kncatl.ohmyworld.expr.ExprEvaluator;

/**
 * 生成期的 BiomeSource 直查（结构高度查询用，1.3.4）。
 *
 * <p>结构起点在 {@code STRUCTURE_STARTS} 阶段计算，此时区块群系尚未填充
 * （{@code ChunkAccess.getNoiseBiome} 只会读到默认值）。公式里的 {@code biomeis}
 * 若参与地形高度，必须直接问生成器的群系源——这里按两代 API 收口：
 * <ul>
 *   <li>1.21.x：{@code BiomeSource.getNoiseBiome(qx, qy, qz, sampler)}；</li>
 *   <li>26.3+：{@code BiomeSource.createResolver(sampler)} → {@code BiomeResolver}。</li>
 * </ul>
 * 采样器也按版本取：26.3 的 {@code RandomState} 已没有 {@code sampler()}，
 * 用 {@code createClimateSampler(SamplerContext.EMPTY_UNCACHED)}。
 */
public final class BiomeViews {

    private BiomeViews() {}

    /** 从 {@link RandomState} 取气候采样器（26.3+ / 旧线两种 API）。 */
    public static Climate.Sampler sampler(RandomState state) {
        //? >=26.3 {
        return state.createClimateSampler(
                net.minecraft.world.level.levelgen.densityfunction.SamplerContext.EMPTY_UNCACHED);
        //?} else {
        return state.sampler();
        //?}
    }

    /**
     * 直接向群系源查询的 {@link ExprEvaluator.BiomeView}（不读区块已填充群系）。
     * source / sampler 为 null 时返回 null（调用方沿用原行为）。
     */
    public static ExprEvaluator.BiomeView view(BiomeSource source, Climate.Sampler sampler) {
        if (source == null || sampler == null) return null;
        //? >=26.3 {
        net.minecraft.world.level.biome.BiomeResolver resolver = source.createResolver(sampler);
        return (x, y, z, biomeId) -> matches(
                resolver.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z)),
                biomeId);
        //?} else {
        return (x, y, z, biomeId) -> matches(
                source.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z), sampler),
                biomeId);
        //?}
    }

    private static boolean matches(Holder<Biome> holder, String biomeId) {
        return holder != null && holder.unwrapKey()
                .map(key -> ResourceIds.keyIdString(key).equals(biomeId))
                .orElse(false);
    }
}
