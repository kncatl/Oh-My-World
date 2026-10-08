package com.kncatl.ohmyworld;

import java.util.stream.Stream;

import com.kncatl.ohmyworld.compat.ResourceIds;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
//? >=26.3 {
import net.minecraft.world.level.biome.BiomeResolver;
//?}

/**
 * 公式群系源的「可序列化外壳」：把运行时群系源注册成 {@code BIOME_SOURCE} 里的一个
 * 正式类型（{@code ohmyworld:formula}），修复「公式群系行接管噪声维度后保存世界崩溃」。
 *
 * <p><b>故障链</b>：噪声型维度（下界/末地/非超平坦主世界）的生成器会把群系源写进
 * 世界数据——{@code NoiseBasedChunkGenerator.CODEC} 的 {@code biome_source} 字段经
 * {@code BiomeSource.CODEC = BIOME_SOURCE.byNameCodec().dispatchStable(...)} 编码；
 * 这个 dispatch 先要拿到「群系源自己的 {@code codec()} 实例」在注册表里的注册名。
 * {@link FormulaBiomeSource} 是运行时构造的、不在注册表里，旧实现返回
 * {@code MapCodec.unit(this)}，于是编码时抛出
 * {@code Unregistered holder in ... worldgen/biome_source}——世界保存直接崩溃
 * （1.21.1 起同一机制；超平坦主世界的 codec 不含 biome_source，所以此前只在
 * 「噪声维度 + 公式群系行」的组合下触发）。
 *
 * <p><b>修复方式</b>：注册本类型（静态 codec 实例），{@link FormulaBiomeSource#codec()}
 * 返回它。存档里只写 {@code type: "ohmyworld:formula"}（负载为空）；读档得到
 * {@link Placeholder}（固定平原）占位源，维度加载后仍由 {@link BiomeControl}
 * 按配置/marker 重新接管，与旧行为完全一致。
 *
 * <p>注册时机：模组初始化（Fabric 入口 / NeoForge 的 {@code RegisterEvent}）。
 * 带本模组保存过的世界数据里会出现 {@code ohmyworld:formula}，此后打开该世界需要
 * 本模组在场。
 */
public final class FormulaBiomeSources {

    /** 注册路径（完整 ID：{@code ohmyworld:formula}）。 */
    public static final String PATH = "formula";

    /**
     * 注册进 {@code BuiltInRegistries.BIOME_SOURCE} 的静态实例。
     *
     * <p>编码/解码与 dispatch 都靠「身份完全相同」来匹配注册名，因此必须只构造一次：
     * {@link #register()} 注册的就是它，{@link FormulaBiomeSource#codec()} 返回的也是它。
     *
     * <p>负载为空、不写任何字段（{@code retrieveGetter} 只从 ops 上下文取群系注册表，
     * 不占用序列化字段）：世界数据里只有类型 ID。
     */
    public static final MapCodec<BiomeSource> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(RegistryOps.retrieveGetter(Registries.BIOME))
                    .apply(instance, FormulaBiomeSources::placeholder));

    private FormulaBiomeSources() {}

    /** 模组初始化时调用（Fabric/NeoForge 两个入口）；重复调用安全。 */
    public static void register() {
        var id = ResourceIds.of("ohmyworld", PATH);
        if (BuiltInRegistries.BIOME_SOURCE.containsKey(id)) return;
        Registry.register(BuiltInRegistries.BIOME_SOURCE, id, CODEC);
    }

    private static BiomeSource placeholder(HolderGetter<Biome> biomeGetter) {
        return new Placeholder(biomeGetter);
    }

    /**
     * 读档占位源：固定平原。只保证「解码后、维度加载并重新接管前」这段时间可用
     * （群系集与逐格查询都返回平原，绝不抛异常）；{@link BiomeControl} 在维度加载时
     * 换成公式源；若该维度的规则已被移除，则还原成该维度的原版群系源
     * （而不是把这个占位源留在世界里）。
     */
    static final class Placeholder extends BiomeSource {
        private final Holder<Biome> plains;

        Placeholder(HolderGetter<Biome> biomeGetter) {
            this.plains = biomeGetter.getOrThrow(Biomes.PLAINS);
        }

        @Override
        protected MapCodec<? extends BiomeSource> codec() {
            // 必须显式限定：BiomeSource.CODEC（Codec<BiomeSource>）作为继承成员
            // 会遮蔽外层类的同名字段。
            return FormulaBiomeSources.CODEC;
        }

        @Override
        protected Stream<Holder<Biome>> collectPossibleBiomes() {
            return Stream.of(plains);
        }

        //? >=26.3 {
        @Override
        public BiomeResolver createResolver(Climate.Sampler sampler) {
            return (qx, qy, qz) -> plains;
        }
        //?} else {
        @Override
        public Holder<Biome> getNoiseBiome(int qx, int qy, int qz, Climate.Sampler sampler) {
            return plains;
        }
        //?}
    }
}
