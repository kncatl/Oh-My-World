package com.kncatl.ohmyworld;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import com.kncatl.ohmyworld.compat.LevelHeights;
import com.kncatl.ohmyworld.expr.ExprEvaluator;
import com.kncatl.ohmyworld.expr.ExprNode;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
//? >=26.3 {
import net.minecraft.world.level.biome.BiomeResolver;
//?}
import org.slf4j.Logger;

/**
 * 公式群系源：按 {@code biome} 行逐格解析群系，未覆盖处按
 * {@code [biome-fallback:...]} 委托原版分布（none / 2d / 3d）。
 *
 * <p>多维度的差异全部由构造参数承担：高度范围（简写层展开与 {@code ly} 基准）、
 * 各维度自己的原版回退源、2d 回退的参考高度（该维度公式地形的表面高度）。
 *
 * <p>版本差异只在覆写点上：1.21.x 覆写 {@code getNoiseBiome(..., Sampler)}，
 * 26.3 覆写 {@code createResolver(Sampler)}（其 {@code BiomeResolver} 不带 sampler，
 * 因此在创建 resolver 时一次性把回退源也绑定好 sampler）。
 *
 * <p>群系以 4×4×4 为一格；采样点取该格起始方块坐标（{@code q << 2}），
 * 与方块层的整数 y 语义一致。同一 y 上后写的层覆盖先写的层。
 */
public class FormulaBiomeSource extends BiomeSource {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 运行期展开后的层：简写层已换成维度真实高度，ly 基准是层起点。 */
    private record Layer(int yStart, int yEnd, int lyOffset, ExprNode expression) {}

    private final List<Layer> layers;
    private final Map<String, Holder<Biome>> biomes;
    private final BiomeSource fallback;
    private final boolean fallback2d;
    private final ExprEvaluator.TerrainView terrain;
    /** 共享气候视图（biome 行用到 climate() 时构建；否则为 null）。 */
    private final ExprEvaluator.ClimateView climate;
    private final AtomicBoolean warned = new AtomicBoolean();

    /**
     * @param fallback    未覆盖处的原版回退源；{@code null} = {@code none}
     * @param fallback2d  true = 2d（按地形表面高度采样一次后覆盖整列）
     * @param terrain     biome 行的公式地形视图（terrain/surfis/blockis 与 2d 参考高度共用）
     * @throws RuntimeException 表达式引用了注册表里不存在的群系（由调用方记日志并保持原状）
     */
    public FormulaBiomeSource(ServerLevel level, List<BiomeLayerDef> defs, BiomeSource fallback,
                              boolean fallback2d, ExprEvaluator.TerrainView terrain) {
        this.fallback = fallback;
        this.fallback2d = fallback2d;
        this.terrain = terrain;
        this.climate = FormulaParser.usesVanillaDataInBiomeLines(defs)
                ? BiomeControl.climateViewFor(level) : null;

        int minY = LevelHeights.minY(level);
        int maxY = LevelHeights.maxY(level) - 1;
        List<Layer> expanded = new ArrayList<>(defs.size());
        for (BiomeLayerDef def : defs) {
            int start = def.resolvedStart(minY);
            int end = def.resolvedEnd(maxY);
            expanded.add(new Layer(start, end, start, def.expression()));
        }
        this.layers = List.copyOf(expanded);

        Map<String, Holder<Biome>> resolved = new HashMap<>();
        var biomeLookup = level.registryAccess().lookupOrThrow(Registries.BIOME);
        for (String id : collectBiomeIds(defs)) {
            resolved.put(id, biomeLookup.getOrThrow(BiomeControl.biomeKey(id)));
        }
        this.biomes = Map.copyOf(resolved);
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        Stream<Holder<Biome>> own = biomes.values().stream();
        return fallback == null ? own : Stream.concat(own, fallback.possibleBiomes().stream());
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        // 注册表里的 ohmyworld:formula 类型（见 FormulaBiomeSources）：
        // 存档只写类型 ID、负载为空，读档得到占位源，维度加载后仍由本模组重新接管。
        // 不能返回 MapCodec.unit(this)——噪声维度的生成器 codec 会把 biome_source 写进
        // 世界数据，dispatch 需要"codec 实例在注册表里的注册名"，unit 实例不在注册表里。
        return FormulaBiomeSources.CODEC;
    }

    //? >=26.3 {
    @Override
    public BiomeResolver createResolver(Climate.Sampler sampler) {
        BiomeResolver fallbackResolver = fallback == null ? null : fallback.createResolver(sampler);
        return (qx, qy, qz) -> {
            Holder<Biome> own = layerBiome(qx, qy, qz);
            if (own != null) return own;
            return fallbackAt(qx, qy, qz, fallbackResolver);
        };
    }

    /** 26.3 形态：回退 resolver 在 createResolver 时一次性创建（避免逐格重绑 sampler）。 */
    private Holder<Biome> fallbackAt(int qx, int qy, int qz, BiomeResolver fallbackResolver) {
        if (fallbackResolver == null) return missingFallback(qx, qy, qz);
        return fallbackResolver.getNoiseBiome(qx, referenceQuartY(qx, qz, qy), qz);
    }
    //?} else {
    @Override
    public Holder<Biome> getNoiseBiome(int qx, int qy, int qz, Climate.Sampler sampler) {
        Holder<Biome> own = layerBiome(qx, qy, qz);
        if (own != null) return own;
        return fallbackAt(qx, qy, qz, sampler);
    }

    /** 1.21.x 形态：回退直接带 sampler 查询。 */
    private Holder<Biome> fallbackAt(int qx, int qy, int qz, Climate.Sampler sampler) {
        if (fallback == null) return missingFallback(qx, qy, qz);
        return fallback.getNoiseBiome(qx, referenceQuartY(qx, qz, qy), qz, sampler);
    }
    //?}

    /** 2d 时把 y 换成该列地形表面高度对应的 quart y；3d/none 原样。 */
    private int referenceQuartY(int qx, int qz, int qy) {
        if (!fallback2d || terrain == null) return qy;
        return terrain.surfaceY(qx << 2, qz << 2) >> 2;
    }

    /** 找到覆盖该格（4×4×4 单元起点）的最后一个 biome 行并求值；无命中返回 null。 */
    private Holder<Biome> layerBiome(int qx, int qy, int qz) {
        int x = qx << 2;
        int y = qy << 2;
        int z = qz << 2;
        for (int i = layers.size() - 1; i >= 0; i--) {
            Layer layer = layers.get(i);
            if (y >= layer.yStart() && y <= layer.yEnd()) {
                return biomeAt(layer, x, y, z);
            }
        }
        return null;
    }

    private Holder<Biome> biomeAt(Layer layer, int x, int y, int z) {
        ExprEvaluator.ClimateView saved = ExprEvaluator.climateView();
        ExprEvaluator.setClimateView(climate);
        try {
            Object result = ExprEvaluator.evalToBiome(layer.expression(), x, z, y - layer.lyOffset(), y,
                    this::resolveBiomeLiteral, terrain);
            if (result instanceof Holder<?> holder && holder.value() instanceof Biome) {
                @SuppressWarnings("unchecked")
                Holder<Biome> biome = (Holder<Biome>) holder;
                return biome;
            }
            return null;
        } finally {
            ExprEvaluator.setClimateView(saved);
        }
    }

    /** 群系字面量的运行期解析：构造期已解析全部 id，这里只查表（缺失是不可达的防御分支）。 */
    private Object resolveBiomeLiteral(String id) {
        Holder<Biome> holder = biomes.get(id);
        if (holder != null) return holder;
        if (warned.compareAndSet(false, true)) {
            LOGGER.warn("ohmyworld: unknown biome \"{}\" at generation time; using a known biome instead", id);
        }
        return biomes.values().iterator().next();
    }

    /**
     * 理论不可达（none 在解析期已要求覆盖整维，运行时未覆盖只可能来自数据包改高度）：
     * 记一次日志并返回一个确定性的安全群系，避免世界生成中途抛异常。
     */
    private Holder<Biome> missingFallback(int qx, int qy, int qz) {
        if (warned.compareAndSet(false, true)) {
            LOGGER.warn("ohmyworld: formula biome uncovered at ({}, {}, {}) without fallback; "
                            + "extend the biome lines or use [biome-fallback:2d|3d]",
                    qx << 2, qy << 2, qz << 2);
        }
        return biomes.values().iterator().next();
    }

    /** 表达式里出现过的全部字面量（群系 id）；构造期一次性解析，未知 id 直接抛错。 */
    private static Set<String> collectBiomeIds(List<BiomeLayerDef> defs) {
        Set<String> ids = new LinkedHashSet<>();
        for (BiomeLayerDef def : defs) collectBiomeIds(def.expression(), ids);
        return ids;
    }

    /**
     * surfis / blockis 的方块参数位（这些位置的 BlockNode 是方块，不是群系）：
     * 未编译形态按名字判断；其余函数返回 -1。
     */
    private static int blockArgumentIndex(String functionName) {
        if (functionName.equals("surfis")) return 2;
        if (functionName.equals("blockis")) return 3;
        return -1;
    }

    /** 编译形态按函数编号判断方块参数位。 */
    private static int blockArgumentIndex(int functionId) {
        if (functionId == ExprEvaluator.FN_SURFIS) return 2;
        if (functionId == ExprEvaluator.FN_BLOCKIS) return 3;
        return -1;
    }

    private static void collectBiomeIds(ExprNode node, Set<String> ids) {
        switch (node) {
            case ExprNode.NumberNode ignored -> {}
            case ExprNode.VariableNode ignored -> {}
            case ExprNode.BlockNode block -> ids.add(block.blockId());
            case ExprNode.BinaryNode binary -> {
                collectBiomeIds(binary.left(), ids);
                collectBiomeIds(binary.right(), ids);
            }
            case ExprNode.UnaryNode unary -> collectBiomeIds(unary.operand(), ids);
            case ExprNode.ConditionalNode conditional -> {
                collectBiomeIds(conditional.condition(), ids);
                collectBiomeIds(conditional.thenExpr(), ids);
                collectBiomeIds(conditional.elseExpr(), ids);
            }
            case ExprNode.FuncCallNode call -> {
                int blockArg = blockArgumentIndex(call.name());
                List<ExprNode> args = call.args();
                for (int i = 0; i < args.size(); i++) {
                    if (i != blockArg) collectBiomeIds(args.get(i), ids);
                }
            }
            case ExprNode.TupleCallNode call -> {
                for (ExprNode arg : call.args()) collectBiomeIds(arg, ids);
            }
            case ExprNode.BlockExprNode block -> {
                for (ExprNode.LetBinding binding : block.bindings()) collectBiomeIds(binding.value(), ids);
                collectBiomeIds(block.body(), ids);
            }
            case ExprNode.BuiltinNode ignored -> {}
            case ExprNode.SlotNode ignored -> {}
            case ExprNode.CompiledFuncCallNode call -> {
                int blockArg = blockArgumentIndex(call.id());
                List<ExprNode> args = call.args();
                for (int i = 0; i < args.size(); i++) {
                    if (i != blockArg) collectBiomeIds(args.get(i), ids);
                }
            }
            case ExprNode.CompiledTupleCallNode call -> {
                for (ExprNode arg : call.args()) collectBiomeIds(arg, ids);
            }
            case ExprNode.TupleComponentNode ignored -> {}
            case ExprNode.CompiledCache2dNode cache -> collectBiomeIds(cache.expr(), ids);
            case ExprNode.CompiledCache3dNode cache -> collectBiomeIds(cache.expr(), ids);
            case ExprNode.CompiledRiverNetNode river -> {
                if (river.coarseExpr() != null) collectBiomeIds(river.coarseExpr(), ids);
            }
            case ExprNode.CompiledBlockNode block -> {
                for (ExprNode value : block.values()) collectBiomeIds(value, ids);
                collectBiomeIds(block.body(), ids);
            }
        }
    }
}
