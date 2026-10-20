package com.kncatl.ohmyworld.compat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import org.slf4j.Logger;

/**
 * TerraBlender 兼容（可选依赖，全部走反射）：
 * TerraBlender 在服务端启动时只初始化 **生成器当前的** {@code MultiNoiseBiomeSource}
 * （{@code LevelUtils.initializeBiomes} 对非 MultiNoise 源直接返回）。本模组的
 * {@code [terrain:vanilla]}/biome 行会把生成器的群系源换成 {@code FormulaBiomeSource}，
 * 于是 TerraBlender 的区域群系（如 More Than Apples 的苹果林/金苹果园/霜苹果林）不会
 * 进入我们新建的原版回退源——表现为“叠加区外找不到该模组的群系”。
 *
 * <p>这里在构建原版回退源后手动调用 TerraBlender 的扩展接口：
 * <ul>
 *   <li>{@code IExtendedParameterList.initializeForTerraBlender(registryAccess, regionType, seed)}
 *       建立区域权重/搜索树；</li>
 *   <li>{@code IExtendedBiomeSource.appendDeferredBiomesList(list)} 把区域群系并入
 *       {@code possibleBiomes}（装饰索引/结构过滤需要它）。</li>
 * </ul>
 * 对已经初始化过的源是幂等的（TerraBlender 内部有 initialized 标记）。
 *
 * <p>没有安装 TerraBlender 时 {@code Class.forName} 直接失败并静默返回；版本差异或
 * 反射失败只记一次日志，不影响世界生成。
 */
public final class TerraBlenderCompat {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean WARNED = new AtomicBoolean();

    private static final String REGION_TYPE = "terrablender.api.RegionType";
    private static final String EXT_PARAM = "terrablender.worldgen.IExtendedParameterList";
    private static final String EXT_BIOME = "terrablender.worldgen.IExtendedBiomeSource";
    private static final String EXT_END = "terrablender.worldgen.IExtendedTheEndBiomeSource";
    private static final String REGIONS = "terrablender.api.Regions";
    private static final String REGION = "terrablender.api.Region";

    private TerraBlenderCompat() {}

    /** 在把 {@code source} 用作原版回退/查询源之前调用；TerraBlender 不在时零开销。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void initialize(BiomeSource source, ServerLevel level) {
        if (source == null || level == null) return;
        try {
            Class<?> regionTypeClass = Class.forName(REGION_TYPE);
            if (source instanceof MultiNoiseBiomeSource multiNoise) {
                Object regionType = Enum.valueOf((Class<? extends Enum>) regionTypeClass,
                        ResourceIds.sameKey(level.dimension(), Level.NETHER) ? "NETHER" : "OVERWORLD");
                initializeMultiNoise(multiNoise, level, regionTypeClass, regionType);
            } else if (source instanceof TheEndBiomeSource end) {
                initializeEnd(end, level);
            }
        } catch (ClassNotFoundException e) {
            // TerraBlender 未安装：正常路径。
        } catch (Throwable t) {
            if (WARNED.compareAndSet(false, true)) {
                LOGGER.error("ohmyworld: TerraBlender compatibility failed; modded biomes may be missing: {}",
                        t.toString());
            }
        }
    }

    private static void initializeMultiNoise(MultiNoiseBiomeSource source, ServerLevel level,
                                             Class<?> regionTypeClass, Object regionType) throws Exception {
        Class<?> extendedParam = Class.forName(EXT_PARAM);
        // 26.3 的 parameters() 是 private（TerraBlender 的 mixin 在运行期把它暴露为 public）；
        // 统一用反射读取，1.21.x 的 public 方法同样适用。
        Method parametersMethod = MultiNoiseBiomeSource.class.getDeclaredMethod("parameters");
        parametersMethod.setAccessible(true);
        Object parameters = parametersMethod.invoke(source);
        if (!extendedParam.isInstance(parameters)) return;

        Method init = extendedParam.getMethod("initializeForTerraBlender",
                RegistryAccess.class, regionTypeClass, long.class);
        init.invoke(parameters, level.registryAccess(), regionType, level.getSeed());

        Class<?> extendedBiome = Class.forName(EXT_BIOME);
        if (!extendedBiome.isInstance(source)) return;
        List<Holder<Biome>> biomes = collectRegionBiomes(level, regionTypeClass, regionType);
        if (!biomes.isEmpty()) {
            Method append = extendedBiome.getMethod("appendDeferredBiomesList", List.class);
            append.invoke(source, biomes);
            LOGGER.info("ohmyworld: TerraBlender contributed {} biome(s) to the vanilla fallback source for {}",
                    biomes.size(), ResourceIds.keyIdString(level.dimension()));
        }
    }

    private static void initializeEnd(TheEndBiomeSource source, ServerLevel level) throws Exception {
        Class<?> extendedEnd = Class.forName(EXT_END);
        if (!extendedEnd.isInstance(source)) return;
        Method init = extendedEnd.getMethod("initializeForTerraBlender", RegistryAccess.class, long.class);
        init.invoke(source, level.registryAccess(), level.getSeed());
    }

    /** 收集 TerraBlender 该维度所有已注册区域的群系（去重，保持注册顺序）。 */
    private static List<Holder<Biome>> collectRegionBiomes(ServerLevel level, Class<?> regionTypeClass,
                                                           Object regionType) throws Exception {
        Class<?> regionsClass = Class.forName(REGIONS);
        Method get = regionsClass.getMethod("get", regionTypeClass);
        Object result = get.invoke(null, regionType);
        if (!(result instanceof List<?> regions)) return List.of();

        Class<?> regionClass = Class.forName(REGION);
        Method addBiomes = regionClass.getMethod("addBiomes", Registry.class, Consumer.class);
        //? >=1.21.2 {
        Registry<Biome> registry = level.registryAccess().lookupOrThrow(Registries.BIOME);
        //?} else {
        Registry<Biome> registry = level.registryAccess().registryOrThrow(Registries.BIOME);
        //?}
        List<Holder<Biome>> out = new ArrayList<>();
        Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> collector = pair -> {
            ResourceKey<Biome> key = pair.getSecond();
            //? >=1.21.2 {
            registry.get(key).ifPresent(holder -> {
                if (!out.contains(holder)) out.add(holder);
            });
            //?} else {
            registry.getHolder(key).ifPresent(holder -> {
                if (!out.contains(holder)) out.add(holder);
            });
            //?}
        };
        for (Object region : regions) {
            addBiomes.invoke(region, registry, collector);
        }
        return out;
    }
}
