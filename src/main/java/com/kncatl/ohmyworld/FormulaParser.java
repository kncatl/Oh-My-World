package com.kncatl.ohmyworld;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

import com.kncatl.ohmyworld.expr.BlockResolver;
import com.kncatl.ohmyworld.expr.ExprCompiler;
import com.kncatl.ohmyworld.expr.ExprEvaluator;
import com.kncatl.ohmyworld.expr.ExprLexer;
import com.kncatl.ohmyworld.expr.ExprNode;
import com.kncatl.ohmyworld.expr.ExprParser;

public class FormulaParser {

    private static final List<String> KNOWN_VARS = List.of("x", "y", "z", "ly", "seed", "spawnx", "spawnz");
    // These are deliberately high safety ceilings, not a formula complexity budget.
    public static final int MAX_INPUT_LENGTH = 1_048_576;
    private static final int MAX_LAYERS = 65_536;

    public record ParseResult(List<Object> layers, List<String> errors, List<BiomeLayerDef> biomeLayers,
                              List<SurfaceLayerDef> surfaceLayers,
                              boolean usesTerrainQueries, boolean usesBiomeQueries) {}

    /** 维度节的规范名（{@link DimensionParseResult} 与 PatternData 的键）。 */
    public static final String DIM_OVERWORLD = "overworld";
    public static final String DIM_NETHER = "the_nether";
    public static final String DIM_END = "the_end";

    /**
     * 解析出的单个维度：层表 + 群系层 + 可选指令（别名与目标共享同一实例）。
     * {@code biomeLayers} 非空表示该维度使用公式群系；{@code biomeFallback} 只在
     * 公式群系下有实际作用（缺省 none）。
     */
    public record ParsedDimension(List<Object> layers, DimensionRules.StructureRule structure,
                                  DimensionRules.BiomeRule biome, boolean featuresOff,
                                  List<BiomeLayerDef> biomeLayers, List<SurfaceLayerDef> surfaceLayers,
                                  DimensionRules.BiomeFallback biomeFallback,
                                  DimensionRules.CarversMode carvers) {}

    /**
     * 按维度的解析结果：{@code dimensions} 键为规范维度名、值为该维度的
     * {@link ParsedDimension}（别名与目标共享同一个实例）；{@code sectioned}
     * 表示输入是否用了 {} 分节语法。
     */
    public record DimensionParseResult(Map<String, ParsedDimension> dimensions, List<String> errors,
                                       boolean sectioned) {}

    /**
     * 指令里可写的原版名称（无命名空间时校验；跨版本并集，26.3 新增项在旧版本上不会命中）。
     * 含自定义命名空间（如 {@code mymod:xxx}）的名称直接放行，供数据包/模组结构使用。
     */
    private static final Set<String> KNOWN_STRUCTURE_NAMES = Set.of(
            "abandoned_camp", "abandoned_camp_bamboo_jungle", "abandoned_camp_birch_forest",
            "abandoned_camp_cherry_grove", "abandoned_camp_dappled_forest", "abandoned_camp_flower_forest",
            "abandoned_camp_forest", "abandoned_camp_meadow", "abandoned_camp_old_growth_birch_forest",
            "abandoned_camp_old_growth_pine_taiga", "abandoned_camp_old_growth_spruce_taiga",
            "abandoned_camp_pale_garden", "abandoned_camp_savanna", "abandoned_camp_snowy_taiga",
            "abandoned_camp_sparse_jungle", "abandoned_camp_swamp", "abandoned_camp_taiga",
            "abandoned_camp_windswept_forest", "abandoned_camp_wooded_badlands", "ancient_cities",
            "ancient_city", "bastion_remnant", "buried_treasure", "buried_treasures", "desert_pyramid",
            "desert_pyramids", "end_cities", "end_city", "fortress", "igloo", "igloos", "jungle_pyramid",
            "jungle_temples", "mansion", "mineshaft", "mineshaft_mesa", "mineshafts", "monument",
            "nether_complexes", "nether_fossil", "nether_fossils", "ocean_monuments", "ocean_ruin_cold",
            "ocean_ruin_warm", "ocean_ruins", "pillager_outpost", "pillager_outposts", "ruined_portal",
            "ruined_portal_desert", "ruined_portal_jungle", "ruined_portal_mountain", "ruined_portal_nether",
            "ruined_portal_ocean", "ruined_portal_swamp", "ruined_portals", "shipwreck", "shipwreck_beached",
            "shipwrecks", "stronghold", "strongholds", "swamp_hut", "swamp_huts", "trail_ruins",
            "trial_chambers", "village_desert", "village_plains", "village_savanna", "village_snowy",
            "village_taiga", "villages", "woodland_mansions");

    private static final Set<String> KNOWN_BIOME_NAMES = Set.of(
            "badlands", "bamboo_jungle", "basalt_deltas", "beach", "birch_forest", "cherry_grove",
            "cold_ocean", "crimson_forest", "dappled_forest", "dark_forest", "deep_cold_ocean", "deep_dark",
            "deep_frozen_ocean", "deep_lukewarm_ocean", "deep_ocean", "desert", "dripstone_caves",
            "end_barrens", "end_highlands", "end_midlands", "eroded_badlands", "flower_forest", "forest",
            "frozen_ocean", "frozen_peaks", "frozen_river", "grove", "ice_spikes", "jagged_peaks", "jungle",
            "lukewarm_ocean", "lush_caves", "mangrove_swamp", "meadow", "mushroom_fields", "nether_wastes",
            "ocean", "old_growth_birch_forest", "old_growth_pine_taiga", "old_growth_spruce_taiga",
            "pale_garden", "plains", "river", "savanna", "savanna_plateau", "small_end_islands",
            "snowy_beach", "snowy_plains", "snowy_slopes", "snowy_taiga", "soul_sand_valley",
            "sparse_jungle", "stony_peaks", "stony_shore", "sulfur_caves", "sunflower_plains", "swamp",
            "taiga", "the_end", "warm_ocean", "warped_forest", "windswept_forest",
            "windswept_gravelly_hills", "windswept_hills", "windswept_savanna", "wooded_badlands");

    public static ParseResult parseWithErrors(String input) {
        if (input == null || input.isBlank()) return invalid("Formula is empty");
        if (input.length() > MAX_INPUT_LENGTH) {
            return invalid("Formula exceeds the maximum input size of " + MAX_INPUT_LENGTH + " characters");
        }

        String cleaned = input.replace("\r", "").replace("\n", "");
        if (cleaned.isBlank()) return invalid("Formula is empty");
        return parseLayers(cleaned);
    }

    /**
     * 按维度的解析入口：输入以 '{' 开头时按分节语法解析，否则等价于
     * {@code {overworld=...}}（旧输入 = 仅主世界，行为与旧版完全一致）。
     */
    public static DimensionParseResult parseDimensionsWithErrors(String input) {
        if (input == null || input.isBlank()) return dimensionInvalid("Formula is empty");
        if (input.length() > MAX_INPUT_LENGTH) {
            return dimensionInvalid("Formula exceeds the maximum input size of " + MAX_INPUT_LENGTH + " characters");
        }

        String cleaned = input.replace("\r", "").replace("\n", "");
        if (cleaned.isBlank()) return dimensionInvalid("Formula is empty");

        if (cleaned.trim().startsWith("{")) return parseSections(cleaned.trim());

        ParseResult result = parseLayers(cleaned);
        List<String> errors = new ArrayList<>(result.errors());
        Map<String, ParsedDimension> dimensions = new LinkedHashMap<>();
        if (errors.isEmpty() && !result.layers().isEmpty()) {
            // 顶格写法无法声明 [biome-fallback:...]，等价于 none：biome 行必须覆盖整维。
            Integer gap = firstUncoveredY(DIM_OVERWORLD, result.biomeLayers());
            if (gap != null) {
                errors.add("Biome lines must cover the whole dimension when biome-fallback is off; y=" + gap
                        + ".." + vanillaMaxY(DIM_OVERWORLD)
                        + " is uncovered (use dimension sections with [biome-fallback:2d|3d] to fall back)");
            } else {
                dimensions.put(DIM_OVERWORLD, new ParsedDimension(result.layers(),
                        DimensionRules.StructureRule.ALL, null, false, result.biomeLayers(),
                        result.surfaceLayers(), DimensionRules.BiomeFallback.NONE,
                        DimensionRules.CarversMode.NONE));
            }
        }
        return new DimensionParseResult(Map.copyOf(dimensions), List.copyOf(errors), false);
    }

    /** 分节语法：{dim=...}{dim=...}；节间允许空白或 ';'，节内容=层语法或维度别名。 */
    private static DimensionParseResult parseSections(String input) {
        Map<String, ParsedDimension> dimensions = new LinkedHashMap<>();
        Map<String, String> aliases = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        int pos = 0;
        int length = input.length();
        int sections = 0;

        while (pos < length) {
            char c = input.charAt(pos);
            if (c == ' ' || c == '\t' || c == ';') {
                pos++;
                continue;
            }
            if (c != '{') {
                errors.add("Unexpected text outside dimension sections: \"" + truncate(input.substring(pos)) + "\"");
                break;
            }
            sections++;

            // 按大括号配对找节尾；节内的 let { } 靠深度计数保护。
            int depth = 1;
            int end = pos + 1;
            while (end < length && depth > 0) {
                char d = input.charAt(end);
                if (d == '{') depth++;
                else if (d == '}') depth--;
                if (depth > 0) end++;
            }
            if (depth != 0) {
                errors.add("Unbalanced '{' in dimension sections");
                break;
            }

            String body = input.substring(pos + 1, end);
            pos = end + 1;
            parseSection(body, dimensions, aliases, errors);
        }

        if (sections == 0 && errors.isEmpty()) errors.add("Formula contains no dimension sections");
        resolveAliases(dimensions, aliases, errors);
        return new DimensionParseResult(Map.copyOf(dimensions), List.copyOf(errors), true);
    }

    /** 解析单个 {名称=内容} 节：内容是维度名 → 别名；否则解析可选指令 + 层语法（错误带维度前缀）。 */
    private static void parseSection(String body, Map<String, ParsedDimension> dimensions,
                                     Map<String, String> aliases, List<String> errors) {
        int eq = body.indexOf('=');
        if (eq < 0) {
            errors.add("Dimension section is missing '='");
            return;
        }
        String rawName = body.substring(0, eq).trim();
        String content = body.substring(eq + 1).trim();
        if (rawName.isEmpty()) {
            errors.add("Dimension section is missing a name before '='");
            return;
        }
        String name = canonicalDimension(rawName);
        if (name == null) {
            errors.add("Unknown dimension \"" + truncate(rawName)
                    + "\" (available: overworld, the_nether, the_end)");
            return;
        }
        if (content.isEmpty()) {
            errors.add(name + ": dimension section is empty");
            return;
        }
        if (dimensions.containsKey(name) || aliases.containsKey(name)) {
            errors.add(name + ": duplicate dimension section");
            return;
        }

        String aliasTarget = canonicalDimension(content);
        if (aliasTarget != null) {
            aliases.put(name, aliasTarget);
            return;
        }

        // 可选指令：[structure:...] / [biome:...] / [features:...]，可各出现一次、顺序任意，之后必须是层语法。
        DimensionRules.StructureRule structure = DimensionRules.StructureRule.ALL;
        DimensionRules.BiomeRule biome = null;
        DimensionRules.BiomeFallback biomeFallback = DimensionRules.BiomeFallback.NONE;
        DimensionRules.CarversMode carvers = DimensionRules.CarversMode.NONE;
        boolean featuresOff = false;
        boolean structureSeen = false;
        boolean biomeSeen = false;
        boolean biomeFallbackSeen = false;
        boolean carversSeen = false;
        boolean featuresSeen = false;
        int pos = 0;
        while (true) {
            while (pos < content.length() && Character.isWhitespace(content.charAt(pos))) pos++;
            if (pos >= content.length() || content.charAt(pos) != '[') break;
            int close = content.indexOf(']', pos + 1);
            if (close < 0) {
                errors.add(name + ": unbalanced '[' in directive");
                return;
            }
            String directive = content.substring(pos + 1, close).trim();
            pos = close + 1;
            if (directive.startsWith("structure:")) {
                if (structureSeen) {
                    errors.add(name + ": duplicate structure directive");
                    return;
                }
                structureSeen = true;
                structure = parseStructureDirective(directive.substring("structure:".length()), name, errors);
                if (structure == null) return;
            } else if (directive.startsWith("biome:")) {
                if (biomeSeen) {
                    errors.add(name + ": duplicate biome directive");
                    return;
                }
                biomeSeen = true;
                biome = parseBiomeDirective(directive.substring("biome:".length()), name, errors);
                if (biome == null) return;
            } else if (directive.startsWith("biome-fallback:")) {
                if (biomeFallbackSeen) {
                    errors.add(name + ": duplicate biome-fallback directive");
                    return;
                }
                biomeFallbackSeen = true;
                biomeFallback = parseBiomeFallbackDirective(
                        directive.substring("biome-fallback:".length()), name, errors);
                if (biomeFallback == null) return;
            } else if (directive.startsWith("carvers:")) {
                if (carversSeen) {
                    errors.add(name + ": duplicate carvers directive");
                    return;
                }
                carversSeen = true;
                carvers = parseCarversDirective(directive.substring("carvers:".length()), name, errors);
                if (carvers == null) return;
            } else if (directive.startsWith("features:")) {
                if (featuresSeen) {
                    errors.add(name + ": duplicate features directive");
                    return;
                }
                featuresSeen = true;
                Boolean off = parseFeaturesDirective(directive.substring("features:".length()), name, errors);
                if (off == null) return;
                featuresOff = off;
            } else {
                errors.add(name + ": unknown directive [" + truncate(directive)
                        + "] (available: structure, biome, biome-fallback, carvers, features)");
                return;
            }
        }

        String layerText = content.substring(pos).trim();
        if (layerText.isEmpty()) {
            errors.add(name + ": dimension section is empty");
            return;
        }
        ParseResult result = parseLayers(layerText);
        for (String error : result.errors()) errors.add(name + ": " + error);
        if (!result.errors().isEmpty()) return;

        if (biome != null && !result.biomeLayers().isEmpty()) {
            errors.add(name + ": [biome:...] cannot be combined with biome lines");
            return;
        }
        if (biomeFallback != DimensionRules.BiomeFallback.NONE && result.biomeLayers().isEmpty()) {
            errors.add(name + ": biome-fallback requires at least one biome line");
            return;
        }
        if (biomeFallback == DimensionRules.BiomeFallback.TWO_D && result.usesBiomeQueries()) {
            errors.add(name + ": biome-fallback 2d cannot be combined with biomeis "
                    + "(the reference height would need biomes that are not filled yet)");
            return;
        }
        if (biomeFallback == DimensionRules.BiomeFallback.NONE) {
            Integer gap = firstUncoveredY(name, result.biomeLayers());
            if (gap != null) {
                errors.add(name + ": biome lines must cover the whole dimension when biome-fallback is off; y="
                        + gap + ".." + vanillaMaxY(name)
                        + " is uncovered (or use [biome-fallback:2d|3d])");
                return;
            }
        }
        if (!result.layers().isEmpty()) {
            dimensions.put(name, new ParsedDimension(result.layers(), structure, biome, featuresOff,
                    result.biomeLayers(), result.surfaceLayers(), biomeFallback, carvers));
        }
    }

    /** [structure:all|none|only=a,b|except=a,b]。 */
    private static DimensionRules.StructureRule parseStructureDirective(String arg, String dimension,
                                                                        List<String> errors) {
        String a = arg.trim();
        if (a.equals("all")) return DimensionRules.StructureRule.ALL;
        if (a.equals("none")) {
            return new DimensionRules.StructureRule(DimensionRules.StructureRule.Mode.NONE, List.of());
        }
        DimensionRules.StructureRule.Mode mode;
        String listPart;
        if (a.startsWith("only=")) {
            mode = DimensionRules.StructureRule.Mode.ONLY;
            listPart = a.substring("only=".length());
        } else if (a.startsWith("except=")) {
            mode = DimensionRules.StructureRule.Mode.EXCEPT;
            listPart = a.substring("except=".length());
        } else {
            errors.add(dimension + ": invalid structure mode \"" + truncate(a)
                    + "\" (use all|none|only=a,b|except=a,b)");
            return null;
        }
        List<String> names = new ArrayList<>();
        for (String raw : listPart.split(",", -1)) {
            String entry = raw.trim();
            if (entry.isEmpty()) {
                errors.add(dimension + ": empty name in structure list");
                return null;
            }
            String normalized = DimensionRules.normalizeName(entry);
            if (!normalized.contains(":") && !KNOWN_STRUCTURE_NAMES.contains(normalized)) {
                errors.add(dimension + ": unknown structure or structure set \"" + entry
                        + "\" (see the guide for available names)");
                return null;
            }
            names.add(normalized);
        }
        if (names.isEmpty()) {
            errors.add(dimension + ": empty structure list");
            return null;
        }
        return new DimensionRules.StructureRule(mode, List.copyOf(names));
    }

    /** [biome:vanilla|&lt;群系id&gt;]。 */
    private static DimensionRules.BiomeRule parseBiomeDirective(String arg, String dimension,
                                                                List<String> errors) {
        String a = arg.trim();
        if (a.isEmpty()) {
            errors.add(dimension + ": empty biome directive");
            return null;
        }
        if (a.equals("vanilla")) return DimensionRules.BiomeRule.VANILLA;
        String normalized = DimensionRules.normalizeName(a);
        if (!isKnownBiome(a)) {
            errors.add(dimension + ": unknown biome \"" + a + "\" (see the guide for available names)");
            return null;
        }
        return DimensionRules.BiomeRule.single(normalized);
    }

    /** [biome-fallback:none|2d|3d]。 */
    private static DimensionRules.BiomeFallback parseBiomeFallbackDirective(String arg, String dimension,
                                                                            List<String> errors) {
        String a = arg.trim();
        if (a.equals("none")) return DimensionRules.BiomeFallback.NONE;
        if (a.equals("2d")) return DimensionRules.BiomeFallback.TWO_D;
        if (a.equals("3d")) return DimensionRules.BiomeFallback.THREE_D;
        errors.add(dimension + ": invalid biome-fallback mode \"" + truncate(a)
                + "\" (available: none, 2d, 3d)");
        return null;
    }

    /** [carvers:none|vanilla]（默认 none：公式接管地形时取消原版雕刻器）。 */
    private static DimensionRules.CarversMode parseCarversDirective(String arg, String dimension,
                                                                     List<String> errors) {
        String a = arg.trim();
        if (a.equals("none")) return DimensionRules.CarversMode.NONE;
        if (a.equals("vanilla")) return DimensionRules.CarversMode.VANILLA;
        errors.add(dimension + ": invalid carvers mode \"" + truncate(a) + "\" (available: none, vanilla)");
        return null;
    }

    /** [features:all|none]；返回 true 表示 none（关闭装饰特性）。 */
    private static Boolean parseFeaturesDirective(String arg, String dimension, List<String> errors) {
        String a = arg.trim();
        if (a.equals("all")) return Boolean.FALSE;
        if (a.equals("none")) return Boolean.TRUE;
        errors.add(dimension + ": invalid features mode \"" + truncate(a) + "\" (use all|none)");
        return null;
    }

    /**
     * 解析别名：目标必须在本输入里显式有公式（链式别名允许；环路/自引用报错）。
     * 成功时别名与目标共享同一个层表实例（各维度仍用自己的高度范围与 ly 语义）。
     */
    private static void resolveAliases(Map<String, ParsedDimension> dimensions, Map<String, String> aliases,
                                       List<String> errors) {
        for (Map.Entry<String, String> entry : aliases.entrySet()) {
            String dimension = entry.getKey();
            List<String> chain = new ArrayList<>();
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            String current = dimension;
            while (true) {
                if (!seen.add(current)) {
                    errors.add(dimension + ": alias cycle (" + String.join(" -> ", chain) + " -> " + current + ")");
                    break;
                }
                chain.add(current);
                ParsedDimension target = dimensions.get(current);
                if (target != null) {
                    dimensions.put(dimension, target);
                    break;
                }
                String next = aliases.get(current);
                if (next == null) {
                    errors.add(dimension + ": alias target \"" + current + "\" is not defined in this formula");
                    break;
                }
                current = next;
            }
        }
    }

    /** 维度名规范化：可写简写（nether/end）与可选 minecraft: 前缀；未知返回 null。 */
    private static String canonicalDimension(String name) {
        String n = name.trim();
        if (n.startsWith("minecraft:")) n = n.substring("minecraft:".length());
        return switch (n) {
            case "overworld" -> DIM_OVERWORLD;
            case "nether", "the_nether" -> DIM_NETHER;
            case "end", "the_end" -> DIM_END;
            default -> null;
        };
    }

    private static DimensionParseResult dimensionInvalid(String error) {
        return new DimensionParseResult(Map.of(), List.of(error), false);
    }

    /** 旧入口的层解析主体（含 smartSplit 分段、共享 let 收集与逐层校验）。 */
    private static ParseResult parseLayers(String cleaned) {
        List<Object> layers = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<BiomeLayerDef> biomeLayers = new ArrayList<>();
        List<SurfaceLayerDef> surfaceLayers = new ArrayList<>();
        boolean usesBiomeQueries = false;
        String[] lines = smartSplit(cleaned);

        // 第一遍：收集共享节级 let（`let 名称 = 表达式` / `let 名称(参数, ...) = 表达式`），
        // 供本段所有层/群系行复用。任一解析失败即整体报错返回：它坏掉时所有层都会
        // 连带报错，噪声很大。
        List<ExprNode.LetBinding> plainLets = new ArrayList<>();
        Map<String, ParametricLet> macros = new LinkedHashMap<>();
        for (int lineIdx = 0; lineIdx < lines.length; lineIdx++) {
            String raw = lines[lineIdx].trim();
            if (raw.isEmpty() || !isSharedLet(raw)) continue;
            try {
                SharedLet parsed = parseSharedLet(raw);
                if (parsed.macro() != null) {
                    if (macros.putIfAbsent(parsed.macro().name(), parsed.macro()) != null) {
                        throw new IllegalArgumentException("duplicate shared let '" + parsed.macro().name() + "'");
                    }
                } else {
                    plainLets.add(parsed.binding());
                }
            } catch (Exception e) {
                errors.add("Shared let (segment " + (lineIdx + 1) + "): " + e.getMessage());
            }
        }
        if (!errors.isEmpty()) return new ParseResult(List.of(), List.copyOf(errors), List.of(), List.of(), false, false);
        // 普通绑定的值也可以调用宏（参数化 let），同样在包装前展开
        List<ExprNode.LetBinding> shared = new ArrayList<>(plainLets.size());
        for (ExprNode.LetBinding binding : plainLets) {
            try {
                shared.add(new ExprNode.LetBinding(binding.names(),
                        expandLoops(expandMacros(binding.value(), macros))));
            } catch (Exception e) {
                errors.add("Shared let '" + String.join(", ", binding.names()) + "': " + e.getMessage());
            }
        }
        if (!errors.isEmpty()) return new ParseResult(List.of(), List.copyOf(errors), List.of(), List.of(), false, false);

        for (int lineIdx = 0; lineIdx < lines.length; lineIdx++) {
            if (layers.size() + biomeLayers.size() + surfaceLayers.size() + errors.size() >= MAX_LAYERS) {
                errors.add("Formula contains too many layers; maximum is " + MAX_LAYERS);
                break;
            }
            String line = lines[lineIdx].trim();
            if (line.isEmpty() || isSharedLet(line)) continue;

            try {
                if (isSurfaceLine(line)) {
                    parseSurfaceLine(line, lineIdx, shared, macros, surfaceLayers, errors);
                    continue;
                }
                if (isBiomeLine(line)) {
                    parseBiomeLayer(line, lineIdx, shared, macros, biomeLayers, errors);
                    continue;
                }
                int colonIdx = findColon(line);
                if (colonIdx < 0) {
                    errors.add(layerError(lineIdx, "missing range separator ':'", line));
                    continue;
                }

                String rangePart = line.substring(0, colonIdx).trim();
                String exprPart = line.substring(colonIdx + 1).trim();
                if (exprPart.isEmpty()) {
                    errors.add(layerError(lineIdx, "empty expression after ':'", line));
                    continue;
                }

                int eqIdx = rangePart.indexOf('=');
                if (eqIdx < 0) {
                    errors.add(layerError(lineIdx, "missing '=' in range \"" + rangePart + "\"", line));
                    continue;
                }
                String varName = rangePart.substring(0, eqIdx).trim();
                if (!varName.equals("y")) {
                    errors.add(layerError(lineIdx, "only 'y' is supported as layer axis, got \"" + varName + "\"", line));
                    continue;
                }

                String valuePart = rangePart.substring(eqIdx + 1).trim();
                int[] range = parseYRange(valuePart);
                int yStart = range[0], yEnd = range[1];
                if (yStart > yEnd) {
                    errors.add(layerError(lineIdx, "range start " + yStart + " is greater than end " + yEnd, line));
                    continue;
                }

                if (exprPart.contains("*[")) {
                    List<CyclicEntry> srcEntries = parseCyclic(exprPart, shared, macros);
                    if (srcEntries.isEmpty()) {
                        errors.add(layerError(lineIdx, "cyclic layer has no valid entries", line));
                        continue;
                    }
                    for (CyclicEntry entry : srcEntries) {
                        if (usesBiomeQuery(entry.expression())) {
                            usesBiomeQueries = true;
                            break;
                        }
                    }
                    // 先按未编译 AST 做语义校验，再编译：编译后的 let 块会变成
                    // CompiledBlockNode，校验器看不到内部结构（旧实现就是先编译后校验，
                    // 这里顺带修掉那个盲区）。
                    List<String> valErrors = new ArrayList<>();
                    for (CyclicEntry e : srcEntries) {
                        ExprEvaluator.ValueType type = validateNode(e.expression(), valErrors, new HashMap<>(), false);
                        if (type != ExprEvaluator.ValueType.BLOCK && type != ExprEvaluator.ValueType.UNKNOWN) {
                            valErrors.add("Cyclic layer expression must return a block, got " + type);
                        }
                    }
                    if (!valErrors.isEmpty()) {
                        for (String ve : valErrors) errors.add(layerError(lineIdx, ve, line));
                        continue;
                    }
                    List<CyclicLayerDef.Entry> entries = new ArrayList<>(srcEntries.size());
                    for (CyclicEntry e : srcEntries) {
                        entries.add(new CyclicLayerDef.Entry(e.thickness(),
                                ExprCompiler.compile(e.expression()), e.lyDependent()));
                    }
                    layers.add(new CyclicLayerDef(yStart, yEnd, entries));
                } else {
                    ExprNode expr = wrapShared(shared,
                            expandLoops(expandMacros(new ExprParser(ExprLexer.tokenize(exprPart)).parse(), macros)));
                    if (!usesBiomeQueries && usesBiomeQuery(expr)) usesBiomeQueries = true;
                    List<String> valErrors = new ArrayList<>();
                    ExprEvaluator.ValueType type = validateNode(expr, valErrors, new HashMap<>(), false);
                    if (type != ExprEvaluator.ValueType.BLOCK && type != ExprEvaluator.ValueType.UNKNOWN) {
                        valErrors.add("Layer expression must return a block, got " + type);
                    }
                    if (!valErrors.isEmpty()) {
                        for (String ve : valErrors) errors.add(layerError(lineIdx, ve, line));
                        continue;
                    }
                    // 「是否与 y 相关」必须在编译前判定：编译后变量变成槽位下标，
                    // 无法再从名字反推来源。
                    boolean lyDependent = ExprEvaluator.dependsOnLy(expr);
                    layers.add(new FormulaLayerDef(yStart, yEnd, ExprCompiler.compile(expr), !lyDependent));
                }
            } catch (StackOverflowError e) {
                errors.add(layerError(lineIdx, "expression nesting is too deep", line));
            } catch (Exception e) {
                errors.add(layerError(lineIdx, e.getMessage(), line));
            }
        }
        if (layers.isEmpty() && errors.isEmpty()) {
            errors.add(biomeLayers.isEmpty()
                    ? "Formula contains no layers"
                    : "Formula contains biome lines but no block layers");
        }
        boolean usesTerrainQueries = false;
        for (BiomeLayerDef layer : biomeLayers) {
            if (usesTerrainQuery(layer.expression())) {
                usesTerrainQueries = true;
                break;
            }
        }
        if (usesTerrainQueries && usesBiomeQueries) {
            errors.add("Biome lines that read the terrain cannot be combined with block layers "
                    + "using biomeis (it would form a cycle)");
        }
        return new ParseResult(List.copyOf(layers), List.copyOf(errors), List.copyOf(biomeLayers),
                List.copyOf(surfaceLayers), usesTerrainQueries, usesBiomeQueries);
    }

    public static List<Object> parse(String input) {
        ParseResult result = parseWithErrors(input);
        if (!result.errors().isEmpty()) throw new IllegalArgumentException(String.join("; ", result.errors()));
        return result.layers();
    }

    private static ParseResult invalid(String error) {
        return new ParseResult(List.of(), List.of(error), List.of(), List.of(), false, false);
    }

    private static String layerError(int lineIdx, String msg, String line) {
        return "Layer " + (lineIdx + 1) + ": " + msg + " in \"" + truncate(line) + "\"";
    }

    private static String truncate(String s) {
        return s.length() <= 60 ? s : s.substring(0, 57) + "...";
    }

    // ---------------------------------------------------------------- 群系行

    /** 段是否是群系行：`biome` 后不跟标识符字符（空白 / `:` / 行尾都算）。 */
    private static boolean isBiomeLine(String line) {
        if (!line.startsWith("biome")) return false;
        if (line.length() == 5) return true;
        char c = line.charAt(5);
        return !(Character.isLetterOrDigit(c) || c == '_');
    }

    /** 群系行的语义错误前缀（与方块层错误区分，便于在编辑器里定位）。 */
    private static String biomeError(int lineIdx, String msg, String line) {
        return "Biome layer " + (lineIdx + 1) + ": " + msg + " in \"" + truncate(line) + "\"";
    }

    /** 群系字面量是否在编译期名单内（裸名查名单；带命名空间放行，运行时再向注册表解析）。 */
    private static boolean isKnownBiome(String biomeId) {
        String normalized = DimensionRules.normalizeName(biomeId);
        return normalized.contains(":") || KNOWN_BIOME_NAMES.contains(normalized);
    }

    /** biome 行的地形查询函数（只能出现在 biome 行；方块参数位按方块语义校验）。 */
    private static boolean isTerrainQueryFunction(String name) {
        return name.equals("terrain") || name.equals("surfis") || name.equals("blockis");
    }

    /** 表达式（含编译形态）是否用到 biomeis。 */
    private static boolean usesBiomeQuery(ExprNode node) {
        return walkFunctions(node, "biomeis"::equals, id -> id == ExprEvaluator.FN_BIOMEIS);
    }

    /** 表达式是否用到 biome 行的地形查询（terrain / surfis / blockis）。 */
    private static boolean usesTerrainQuery(ExprNode node) {
        return walkFunctions(node, FormulaParser::isTerrainQueryFunction,
                id -> id == ExprEvaluator.FN_TERRAIN || id == ExprEvaluator.FN_SURFIS
                        || id == ExprEvaluator.FN_BLOCKIS);
    }

    /** 遍历表达式里的函数调用（未编译按名字、编译后按编号）。 */
    private static boolean walkFunctions(ExprNode node, Predicate<String> byName, IntPredicate byId) {
        return switch (node) {
            case ExprNode.NumberNode ignored -> false;
            case ExprNode.VariableNode ignored -> false;
            case ExprNode.BlockNode ignored -> false;
            case ExprNode.BinaryNode binary -> walkFunctions(binary.left(), byName, byId)
                    || walkFunctions(binary.right(), byName, byId);
            case ExprNode.UnaryNode unary -> walkFunctions(unary.operand(), byName, byId);
            case ExprNode.ConditionalNode conditional ->
                    walkFunctions(conditional.condition(), byName, byId)
                            || walkFunctions(conditional.thenExpr(), byName, byId)
                            || walkFunctions(conditional.elseExpr(), byName, byId);
            case ExprNode.FuncCallNode call -> byName.test(call.name())
                    || call.args().stream().anyMatch(arg -> walkFunctions(arg, byName, byId));
            case ExprNode.TupleCallNode call -> byName.test(call.name())
                    || call.args().stream().anyMatch(arg -> walkFunctions(arg, byName, byId));
            case ExprNode.BlockExprNode block ->
                    block.bindings().stream().anyMatch(b -> walkFunctions(b.value(), byName, byId))
                            || walkFunctions(block.body(), byName, byId);
            case ExprNode.BuiltinNode ignored -> false;
            case ExprNode.SlotNode ignored -> false;
            case ExprNode.CompiledFuncCallNode call -> byId.test(call.id())
                    || call.args().stream().anyMatch(arg -> walkFunctions(arg, byName, byId));
            case ExprNode.CompiledTupleCallNode call -> byId.test(call.id())
                    || call.args().stream().anyMatch(arg -> walkFunctions(arg, byName, byId));
            case ExprNode.TupleComponentNode ignored -> false;
            case ExprNode.CompiledCache2dNode cache -> walkFunctions(cache.expr(), byName, byId);
            case ExprNode.CompiledCache3dNode cache -> walkFunctions(cache.expr(), byName, byId);
            case ExprNode.CompiledRiverNetNode river ->
                    river.coarseExpr() != null && walkFunctions(river.coarseExpr(), byName, byId);
            case ExprNode.CompiledBlockNode block ->
                    Arrays.stream(block.values()).anyMatch(v -> walkFunctions(v, byName, byId))
                            || walkFunctions(block.body(), byName, byId);
        };
    }

    /** 原版维度最低高度（含）。分节语法只支持原版三维度，与预览的假设一致。 */
    public static int vanillaMinY(String dimension) {
        return DIM_OVERWORLD.equals(dimension) ? -64 : 0;
    }

    /** 原版维度最高高度（含）。 */
    public static int vanillaMaxY(String dimension) {
        return DIM_OVERWORLD.equals(dimension) ? 319 : 255;
    }

    /**
     * biome-fallback=none 的覆盖检查：返回第一个未被任何群系行覆盖的 y；
     * 空表或已覆盖整维返回 null（简写行覆盖整维）。
     */
    private static Integer firstUncoveredY(String dimension, List<BiomeLayerDef> biomeLayers) {
        if (biomeLayers.isEmpty()) return null;
        int min = vanillaMinY(dimension);
        int max = vanillaMaxY(dimension);
        List<int[]> ranges = new ArrayList<>();
        for (BiomeLayerDef layer : biomeLayers) {
            if (layer.shorthand()) return null;
            ranges.add(new int[] {layer.yStart(), layer.yEnd()});
        }
        ranges.sort((a, b) -> Integer.compare(a[0], b[0]));
        int cursor = min;
        for (int[] range : ranges) {
            if (range[1] < cursor) continue;
            if (range[0] > cursor) return cursor;
            if (range[1] >= max) return null;
            cursor = range[1] + 1;
        }
        return cursor <= max ? cursor : null;
    }

    /**
     * 解析 y 范围：{@code a}、{@code a..b}、{@code a..}、{@code ..b}、{@code ..}。
     * 开区间一侧用哨兵表示（{@code Integer.MIN_VALUE}/{@code Integer.MAX_VALUE}），
     * 运行期按维度真实高度解析——起止与 {@code ly} 基准同 biome 简写同款处理
     * （{@code ly = y - 起点}，起点缺省时取维度最低 y）。
     */
    private static int[] parseYRange(String valuePart) {
        int dotsIdx = valuePart.indexOf("..");
        if (dotsIdx < 0) {
            int y = Integer.parseInt(valuePart);
            return new int[] {y, y};
        }
        String startPart = valuePart.substring(0, dotsIdx).trim();
        String endPart = valuePart.substring(dotsIdx + 2).trim();
        int yStart = startPart.isEmpty() ? Integer.MIN_VALUE : Integer.parseInt(startPart);
        int yEnd = endPart.isEmpty() ? Integer.MAX_VALUE : Integer.parseInt(endPart);
        return new int[] {yStart, yEnd};
    }

    /** 段是否是 surface 行：{@code surface} 后不跟标识符字符（空白 / '[' / ':' / 行尾都算）。 */
    private static boolean isSurfaceLine(String line) {
        if (!line.startsWith("surface")) return false;
        if (line.length() == 7) return true;
        char c = line.charAt(7);
        return !(Character.isLetterOrDigit(c) || c == '_');
    }

    /**
     * 解析一行表面规则：
     * {@code surface y=a..b: 表达式}、{@code surface: 表达式}（整维简写）、
     * 可选前缀 {@code surface[maxdepth=N]}（N ∈ 1..64，默认 8）。
     */
    private static void parseSurfaceLine(String line, int lineIdx, List<ExprNode.LetBinding> shared,
                                         Map<String, ParametricLet> macros,
                                         List<SurfaceLayerDef> surfaceLayers, List<String> errors) {
        String rest = line.substring("surface".length()).trim();
        int maxDepth = 8;
        if (rest.startsWith("[")) {
            int close = rest.indexOf(']');
            if (close < 0) throw new IllegalArgumentException("unbalanced '[' in surface modifier");
            String modifier = rest.substring(1, close).trim();
            rest = rest.substring(close + 1).trim();
            if (!modifier.startsWith("maxdepth=")) {
                throw new IllegalArgumentException("unknown surface modifier: [" + modifier + "]");
            }
            String value = modifier.substring("maxdepth=".length()).trim();
            try {
                maxDepth = Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("surface maxdepth must be an integer, got \"" + value + "\"");
            }
            if (maxDepth < 1 || maxDepth > 64) {
                throw new IllegalArgumentException("surface maxdepth must be in 1..64, got " + maxDepth);
            }
        }
        boolean shorthand;
        int yStart = Integer.MIN_VALUE;
        int yEnd = Integer.MAX_VALUE;
        String exprPart;
        if (rest.startsWith(":")) {
            shorthand = true;
            exprPart = rest.substring(1).trim();
        } else {
            shorthand = false;
            int colonIdx = findColon(rest);
            if (colonIdx < 0) throw new IllegalArgumentException("missing range separator ':' in surface line");
            String rangePart = rest.substring(0, colonIdx).trim();
            exprPart = rest.substring(colonIdx + 1).trim();
            int eqIdx = rangePart.indexOf('=');
            if (eqIdx < 0) throw new IllegalArgumentException("missing '=' in range \"" + rangePart + "\"");
            if (!rangePart.substring(0, eqIdx).trim().equals("y")) {
                throw new IllegalArgumentException("only 'y' is supported as surface axis");
            }
            int[] range = parseYRange(rangePart.substring(eqIdx + 1).trim());
            yStart = range[0];
            yEnd = range[1];
            if (yStart > yEnd) {
                throw new IllegalArgumentException("range start " + yStart + " is greater than end " + yEnd);
            }
        }
        if (exprPart.isEmpty()) throw new IllegalArgumentException("empty expression after ':'");

        ExprNode expr = wrapShared(shared,
                expandLoops(expandMacros(new ExprParser(ExprLexer.tokenize(exprPart)).parse(), macros)));
        // surface 行是"方块语义"：把它当方块层的变体校验（biomeis 可用、terrain 不可用）；
        // sd/sdb/wd/slope/keep 通过校验变量表注入（只在表面行合法）。
        Map<String, ExprEvaluator.ValueType> surfaceVars = new HashMap<>();
        surfaceVars.put("sd", ExprEvaluator.ValueType.NUMBER);
        surfaceVars.put("sdb", ExprEvaluator.ValueType.NUMBER);
        surfaceVars.put("wd", ExprEvaluator.ValueType.NUMBER);
        surfaceVars.put("slope", ExprEvaluator.ValueType.NUMBER);
        surfaceVars.put("keep", ExprEvaluator.ValueType.BLOCK);
        List<String> valErrors = new ArrayList<>();
        ExprEvaluator.ValueType type = validateNode(expr, valErrors, surfaceVars, false);
        if (type != ExprEvaluator.ValueType.BLOCK && type != ExprEvaluator.ValueType.UNKNOWN) {
            valErrors.add("Surface expression must return a block or keep, got " + type);
        }
        if (!valErrors.isEmpty()) {
            for (String ve : valErrors) errors.add(layerError(lineIdx, ve, line));
            return;
        }
        surfaceLayers.add(new SurfaceLayerDef(yStart, yEnd, shorthand, maxDepth, ExprCompiler.compile(expr)));
    }

    /** 解析一行群系层：{@code biome: 表达式}（整维简写）或 {@code biome y=a..b: 表达式}。 */
    private static void parseBiomeLayer(String line, int lineIdx, List<ExprNode.LetBinding> shared,
                                        Map<String, ParametricLet> macros,
                                        List<BiomeLayerDef> biomeLayers, List<String> errors) {
        try {
            String rest = line.substring("biome".length()).trim();
            boolean shorthand;
            int yStart;
            int yEnd;
            String exprPart;
            if (rest.startsWith(":")) {
                shorthand = true;
                yStart = Integer.MIN_VALUE;
                yEnd = Integer.MAX_VALUE;
                exprPart = rest.substring(1).trim();
            } else {
                if (!rest.startsWith("y")) {
                    throw new IllegalArgumentException("expected 'biome:' or 'biome y=a..b:'");
                }
                shorthand = false;
                int colonIdx = findColon(rest);
                if (colonIdx < 0) throw new IllegalArgumentException("missing range separator ':'");
                String rangePart = rest.substring(0, colonIdx).trim();
                exprPart = rest.substring(colonIdx + 1).trim();
                int eqIdx = rangePart.indexOf('=');
                if (eqIdx < 0) throw new IllegalArgumentException("missing '=' in range \"" + rangePart + "\"");
                String varName = rangePart.substring(0, eqIdx).trim();
                if (!varName.equals("y")) {
                    throw new IllegalArgumentException("only 'y' is supported as layer axis, got \"" + varName + "\"");
                }
                String valuePart = rangePart.substring(eqIdx + 1).trim();
                int[] range = parseYRange(valuePart);
                yStart = range[0];
                yEnd = range[1];
                if (yStart > yEnd) {
                    throw new IllegalArgumentException("range start " + yStart + " is greater than end " + yEnd);
                }
            }
            if (exprPart.isEmpty()) throw new IllegalArgumentException("empty expression after ':'");

            ExprNode expr = wrapShared(shared,
                    expandLoops(expandMacros(new ExprParser(ExprLexer.tokenize(exprPart)).parse(), macros)));
            // biome 行的 vanilla 群系值通过校验变量表注入（只在 biome 行合法；
            // 编译器统一把标识符 vanilla 编译成哨兵节点，位置靠这里把关）。
            Map<String, ExprEvaluator.ValueType> biomeVars = new HashMap<>();
            biomeVars.put("vanilla", ExprEvaluator.ValueType.BLOCK);
            List<String> valErrors = new ArrayList<>();
            ExprEvaluator.ValueType type = validateNode(expr, valErrors, biomeVars, true);
            if (type != ExprEvaluator.ValueType.BLOCK && type != ExprEvaluator.ValueType.UNKNOWN) {
                valErrors.add("Biome layer expression must return a biome, got " + type);
            }
            if (!valErrors.isEmpty()) {
                for (String ve : valErrors) errors.add(biomeError(lineIdx, ve, line));
                return;
            }
            // 与方块层同理：「是否与 y 相关」必须在编译前判定（编译后变量变成槽位下标）。
            boolean lyDependent = ExprEvaluator.dependsOnLy(expr);
            biomeLayers.add(new BiomeLayerDef(yStart, yEnd, shorthand,
                    ExprCompiler.compile(expr), !lyDependent));
        } catch (StackOverflowError e) {
            errors.add(biomeError(lineIdx, "expression nesting is too deep", line));
        } catch (Exception e) {
            errors.add(biomeError(lineIdx, e.getMessage(), line));
        }
    }

    /**
     * 语义校验：同时推导表达式类型，避免非法结果在运行时静默变成空气。
     *
     * <p>{@code biomeMode=true} 时按"群系表达式"校验：字面量查群系名单（带命名空间
     * 放行、运行期再向注册表解析）、rand/randexcept 不可用，其余规则一致；群系值
     * 沿用 {@code BLOCK} 类型槽位表示"不可参与算术的不透明值"。
     */
    private static ExprEvaluator.ValueType validateNode(ExprNode node, List<String> errors,
                                                        Map<String, ExprEvaluator.ValueType> variables,
                                                        boolean biomeMode) {
        switch (node) {
            case ExprNode.NumberNode n -> { return ExprEvaluator.ValueType.NUMBER; }
            case ExprNode.VariableNode v -> {
                if (variables.containsKey(v.name())) return variables.get(v.name());
                if (KNOWN_VARS.contains(v.name())) return ExprEvaluator.ValueType.NUMBER;
                errors.add("Unknown variable: " + v.name() + " (available: x, y, z, ly, seed, spawnx, spawnz)");
                return ExprEvaluator.ValueType.UNKNOWN;
            }
            case ExprNode.BlockNode b -> {
                if (biomeMode) {
                    if (!isKnownBiome(b.blockId())) {
                        errors.add("Unknown biome: " + b.blockId());
                        return ExprEvaluator.ValueType.UNKNOWN;
                    }
                    return ExprEvaluator.ValueType.BLOCK;
                }
                if (!BlockResolver.exists(b.blockId())) {
                    errors.add("Unknown block: " + b.blockId());
                    return ExprEvaluator.ValueType.UNKNOWN;
                }
                return ExprEvaluator.ValueType.BLOCK;
            }
            case ExprNode.BinaryNode bn -> {
                ExprEvaluator.ValueType left = validateNode(bn.left(), errors, variables, biomeMode);
                ExprEvaluator.ValueType right = validateNode(bn.right(), errors, variables, biomeMode);
                return switch (bn.op()) {
                    case ADD, SUB, MUL, DIV, MOD -> {
                        requireNumber(left, "left operand of " + bn.op(), errors);
                        requireNumber(right, "right operand of " + bn.op(), errors);
                        yield ExprEvaluator.ValueType.NUMBER;
                    }
                    case EQ, NE -> {
                        if (left != ExprEvaluator.ValueType.UNKNOWN && right != ExprEvaluator.ValueType.UNKNOWN && left != right) {
                            errors.add("Cannot compare " + left + " with " + right);
                        }
                        yield ExprEvaluator.ValueType.BOOLEAN;
                    }
                    case LT, GT, LE, GE -> {
                        requireNumber(left, "left operand of " + bn.op(), errors);
                        requireNumber(right, "right operand of " + bn.op(), errors);
                        yield ExprEvaluator.ValueType.BOOLEAN;
                    }
                    case AND, OR -> {
                        requireCondition(left, "left operand of " + bn.op(), errors);
                        requireCondition(right, "right operand of " + bn.op(), errors);
                        yield ExprEvaluator.ValueType.BOOLEAN;
                    }
                };
            }
            case ExprNode.UnaryNode u -> {
                ExprEvaluator.ValueType operand = validateNode(u.operand(), errors, variables, biomeMode);
                if (u.op() == ExprNode.UnaryOp.NOT) {
                    requireCondition(operand, "operand of !", errors);
                    return ExprEvaluator.ValueType.BOOLEAN;
                }
                requireNumber(operand, "operand of unary -", errors);
                return ExprEvaluator.ValueType.NUMBER;
            }
            case ExprNode.ConditionalNode c -> {
                ExprEvaluator.ValueType condition = validateNode(c.condition(), errors, variables, biomeMode);
                requireCondition(condition, "ternary condition", errors);
                ExprEvaluator.ValueType thenType = validateNode(c.thenExpr(), errors, variables, biomeMode);
                ExprEvaluator.ValueType elseType = validateNode(c.elseExpr(), errors, variables, biomeMode);
                if (thenType != ExprEvaluator.ValueType.UNKNOWN && elseType != ExprEvaluator.ValueType.UNKNOWN && thenType != elseType) {
                    errors.add("Ternary branches must return the same type, got " + thenType + " and " + elseType);
                    return ExprEvaluator.ValueType.UNKNOWN;
                }
                return thenType == ExprEvaluator.ValueType.UNKNOWN ? elseType : thenType;
            }
            case ExprNode.FuncCallNode f -> {
                if (f.name().equals("cache2d") || f.name().equals("cache3d")) {
                    validateCacheCall(f, errors, variables, biomeMode);
                    return ExprEvaluator.ValueType.NUMBER;
                }
                if (f.name().equals("climate")) {
                    validateClimateCall(f, errors, variables, biomeMode);
                    return ExprEvaluator.ValueType.NUMBER;
                }
                if (f.name().equals("df") || f.name().equals("noise")) {
                    validateRegistryCall(f, errors, variables, biomeMode);
                    return ExprEvaluator.ValueType.NUMBER;
                }
                if (f.name().equals("biome_at")) {
                    // 原版群系参数表查询（M3.3）：位置规则同 terrain 族——只在 biome 行可用
                    String arity = ExprEvaluator.validateFunction("biome_at", f.args().size());
                    if (arity != null) errors.add(arity);
                    if (!biomeMode) {
                        errors.add("Function 'biome_at' can only be used in biome lines");
                        for (ExprNode a : f.args()) validateNode(a, errors, variables, false);
                        return ExprEvaluator.ValueType.UNKNOWN;
                    }
                    for (ExprNode a : f.args()) {
                        requireNumber(validateNode(a, errors, variables, true), "argument of biome_at", errors);
                    }
                    return ExprEvaluator.ValueType.BLOCK;
                }
                boolean terrainQuery = isTerrainQueryFunction(f.name());
                if (terrainQuery && !biomeMode) {
                    errors.add("Function '" + f.name() + "' can only be used in biome lines");
                    for (ExprNode a : f.args()) validateNode(a, errors, variables, false);
                    return ExprEvaluator.ValueType.UNKNOWN;
                }
                if (f.name().equals("biomeis") && biomeMode) {
                    errors.add("Function 'biomeis' can only be used in block layers");
                    for (ExprNode a : f.args()) validateNode(a, errors, variables, true);
                    return ExprEvaluator.ValueType.UNKNOWN;
                }
                if (biomeMode && (f.name().equals("rand") || f.name().equals("randexcept"))) {
                    errors.add("Function '" + f.name() + "' cannot be used in a biome expression");
                    for (ExprNode a : f.args()) validateNode(a, errors, variables, true);
                    return ExprEvaluator.ValueType.UNKNOWN;
                }
                String msg = ExprEvaluator.validateFunction(f.name(), f.args().size());
                if (msg != null) {
                    errors.add(msg);
                    for (ExprNode a : f.args()) validateNode(a, errors, variables, biomeMode);
                    return ExprEvaluator.ValueType.UNKNOWN;
                }
                if (terrainQuery) {
                    // biomeMode == true（非 biome 模式已在上面拦截）
                    if (f.name().equals("terrain")) {
                        for (ExprNode a : f.args()) {
                            requireNumber(validateNode(a, errors, variables, true), "argument of terrain", errors);
                        }
                        return ExprEvaluator.ValueType.NUMBER;
                    }
                    // surfis(x, z, 方块) / blockis(x, z, y, 方块)：最后一个参数按方块校验
                    int blockArg = f.name().equals("surfis") ? 2 : 3;
                    for (int i = 0; i < f.args().size(); i++) {
                        ExprEvaluator.ValueType type = validateNode(f.args().get(i), errors, variables,
                                i != blockArg);
                        if (i == blockArg) {
                            if (type != ExprEvaluator.ValueType.BLOCK && type != ExprEvaluator.ValueType.UNKNOWN) {
                                errors.add("Function '" + f.name() + "' expects a block as its last argument, got " + type);
                            }
                        } else {
                            requireNumber(type, "argument of " + f.name(), errors);
                        }
                    }
                    return ExprEvaluator.ValueType.BOOLEAN;
                }
                if (f.name().equals("biomeis")) {
                    // biomeMode == false（biome 模式已在上面拦截）；末位是群系表达式：
                    // 单个群系字面量，或三元等组合（按群系语义校验）。
                    for (int i = 0; i < f.args().size() - 1; i++) {
                        requireNumber(validateNode(f.args().get(i), errors, variables, false),
                                "argument of biomeis", errors);
                    }
                    ExprNode biomeArg = f.args().get(f.args().size() - 1);
                    if (usesTerrainQuery(biomeArg)) {
                        errors.add("Function 'biomeis' cannot use terrain queries in its biome expression"
                                + " (terrain queries are limited to biome lines)");
                    }
                    ExprEvaluator.ValueType biomeType = validateNode(biomeArg, errors, variables, true);
                    if (biomeType != ExprEvaluator.ValueType.BLOCK && biomeType != ExprEvaluator.ValueType.UNKNOWN) {
                        errors.add("Function 'biomeis' expects a biome (literal or biome expression)"
                                + " as its last argument, got " + biomeType);
                    }
                    return ExprEvaluator.ValueType.BOOLEAN;
                }
                if (f.name().equals("rand") || f.name().equals("randexcept")) {
                    for (ExprNode a : f.args()) {
                        ExprEvaluator.ValueType type = validateNode(a, errors, variables, biomeMode);
                        if (type != ExprEvaluator.ValueType.BLOCK && type != ExprEvaluator.ValueType.UNKNOWN) {
                            errors.add("Function '" + f.name() + "' expects block arguments, got " + type);
                        }
                    }
                    return ExprEvaluator.ValueType.BLOCK;
                }
                if (f.name().equals("spline") || f.name().equals("cspline")) {
                    checkAscendingPoints(f.name(), f.args(), errors);
                }
                for (ExprNode a : f.args()) {
                    ExprEvaluator.ValueType type = validateNode(a, errors, variables, biomeMode);
                    requireNumber(type, "argument of " + f.name(), errors);
                }
                return ExprEvaluator.ValueType.NUMBER;
            }
            case ExprNode.BlockExprNode be -> {
                Map<String, ExprEvaluator.ValueType> local = new HashMap<>(variables);
                for (ExprNode.LetBinding lb : be.bindings()) {
                    if (lb.names().size() == 1) {
                        local.put(lb.names().get(0), validateNode(lb.value(), errors, local, biomeMode));
                    } else {
                        validateTupleBinding(lb, errors, local, biomeMode);
                        for (String name : lb.names()) local.put(name, ExprEvaluator.ValueType.NUMBER);
                    }
                }
                return validateNode(be.body(), errors, local, biomeMode);
            }
            case ExprNode.TupleCallNode t -> {
                errors.add("Function '" + t.name() + "' returns multiple values: it can only be used "
                        + "as the right-hand side of 'let (a, b, ...) = ...'");
                for (ExprNode a : t.args()) validateNode(a, errors, variables, biomeMode);
                return ExprEvaluator.ValueType.UNKNOWN;
            }
            // 以下是编译后的形态。语义校验发生在编译之前（见本文件的处理顺序），
            // 因此这几个分支实际不会走到；这里只是为了让 switch 穷尽。
            case ExprNode.BuiltinNode b -> { return ExprEvaluator.ValueType.NUMBER; }
            case ExprNode.SlotNode s -> { return ExprEvaluator.ValueType.UNKNOWN; }
            case ExprNode.CompiledFuncCallNode cf -> { return ExprEvaluator.ValueType.UNKNOWN; }
            case ExprNode.CompiledTupleCallNode cf -> { return ExprEvaluator.ValueType.UNKNOWN; }
            case ExprNode.TupleComponentNode tc -> { return ExprEvaluator.ValueType.NUMBER; }
            case ExprNode.CompiledCache2dNode c -> { return ExprEvaluator.ValueType.NUMBER; }
            case ExprNode.CompiledCache3dNode c -> { return ExprEvaluator.ValueType.NUMBER; }
            case ExprNode.CompiledRiverNetNode r -> { return ExprEvaluator.ValueType.UNKNOWN; }
            case ExprNode.CompiledBlockNode cb -> { return ExprEvaluator.ValueType.UNKNOWN; }
        }
    }

    /** cache2d / cache3d 的专项校验：表达式自包含、不引用 ly（cache2d 也不引用 y）、不含视图/随机函数。 */
    private static void validateCacheCall(ExprNode.FuncCallNode f, List<String> errors,
                                          Map<String, ExprEvaluator.ValueType> variables, boolean biomeMode) {
        String name = f.name();
        int argCount = f.args().size();
        if (name.equals("cache2d")) {
            if (argCount < 1 || argCount > 2) {
                errors.add("Function 'cache2d' expects 1 or 2 arguments (expression[, step]), got " + argCount);
                return;
            }
        } else if (argCount != 1 && argCount != 4) {
            errors.add("Function 'cache3d' expects 1 or 4 arguments (expression[, sx, sy, sz]), got " + argCount);
            return;
        }
        for (int i = 1; i < argCount; i++) {
            ExprNode arg = f.args().get(i);
            if (!(arg instanceof ExprNode.NumberNode n)
                    || n.value() != Math.rint(n.value())
                    || n.value() < 1 || n.value() > 16) {
                errors.add("Function '" + name + "': step arguments must be integer literals in 1..16");
                break;
            }
        }
        ExprNode expr = f.args().get(0);
        ExprEvaluator.ValueType type = validateNode(expr, errors, variables, biomeMode);
        requireNumber(type, "first argument of " + name, errors);
        if (referencesBoundVariables(expr, variables, new HashSet<>())) {
            errors.add("Function '" + name + "': the expression must be self-contained "
                    + "(built-ins only; no let bindings or surface variables)");
        }
        if (containsCacheForbiddenFunctions(expr)) {
            errors.add("Function '" + name + "': the expression cannot use "
                    + "terrain/surfis/blockis/biomeis/rand/randexcept");
        }
        boolean forbidY = name.equals("cache2d");
        if (ExprCompiler.usesVertical(expr, forbidY)) {
            errors.add("Function '" + name + "': the expression cannot reference "
                    + (forbidY ? "ly or y" : "ly"));
        }
    }

    /** 表达式是否引用外层 let 绑定（自包含检查；内层 let 遮蔽不计）。 */
    private static boolean referencesBoundVariables(ExprNode node,
                                                    Map<String, ExprEvaluator.ValueType> variables,
                                                    Set<String> shadowed) {
        return switch (node) {
            case ExprNode.NumberNode n -> false;
            case ExprNode.BlockNode b -> false;
            case ExprNode.VariableNode v -> !shadowed.contains(v.name()) && variables.containsKey(v.name());
            case ExprNode.BinaryNode b -> referencesBoundVariables(b.left(), variables, shadowed)
                    || referencesBoundVariables(b.right(), variables, shadowed);
            case ExprNode.UnaryNode u -> referencesBoundVariables(u.operand(), variables, shadowed);
            case ExprNode.ConditionalNode c -> referencesBoundVariables(c.condition(), variables, shadowed)
                    || referencesBoundVariables(c.thenExpr(), variables, shadowed)
                    || referencesBoundVariables(c.elseExpr(), variables, shadowed);
            case ExprNode.FuncCallNode f -> f.args().stream()
                    .anyMatch(arg -> referencesBoundVariables(arg, variables, shadowed));
            case ExprNode.TupleCallNode t -> t.args().stream()
                    .anyMatch(arg -> referencesBoundVariables(arg, variables, shadowed));
            case ExprNode.BlockExprNode be -> {
                Set<String> inner = new HashSet<>(shadowed);
                boolean found = false;
                for (ExprNode.LetBinding binding : be.bindings()) {
                    if (referencesBoundVariables(binding.value(), variables, inner)) {
                        found = true;
                        break;
                    }
                    inner.addAll(binding.names());
                }
                yield found || referencesBoundVariables(be.body(), variables, inner);
            }
            case ExprNode.BuiltinNode b -> false;
            // 以下形态不会出现在校验前的 AST 里；保守视为引用
            case ExprNode.SlotNode s -> true;
            case ExprNode.CompiledFuncCallNode cf -> true;
            case ExprNode.CompiledTupleCallNode ct -> true;
            case ExprNode.TupleComponentNode tc -> true;
            case ExprNode.CompiledCache2dNode c2 -> true;
            case ExprNode.CompiledCache3dNode c3 -> true;
            case ExprNode.CompiledRiverNetNode r -> true;
            case ExprNode.CompiledBlockNode cb -> true;
        };
    }

    /** 表达式是否含 cache 禁列函数（视图 / 随机）。 */
    private static boolean containsCacheForbiddenFunctions(ExprNode node) {
        return switch (node) {
            case ExprNode.NumberNode n -> false;
            case ExprNode.BlockNode b -> false;
            case ExprNode.VariableNode v -> false;
            case ExprNode.BinaryNode b -> containsCacheForbiddenFunctions(b.left())
                    || containsCacheForbiddenFunctions(b.right());
            case ExprNode.UnaryNode u -> containsCacheForbiddenFunctions(u.operand());
            case ExprNode.ConditionalNode c -> containsCacheForbiddenFunctions(c.condition())
                    || containsCacheForbiddenFunctions(c.thenExpr())
                    || containsCacheForbiddenFunctions(c.elseExpr());
            case ExprNode.FuncCallNode f -> {
                if (isTerrainQueryFunction(f.name()) || f.name().equals("biomeis")
                        || f.name().equals("rand") || f.name().equals("randexcept")) {
                    yield true;
                }
                yield f.args().stream().anyMatch(FormulaParser::containsCacheForbiddenFunctions);
            }
            case ExprNode.TupleCallNode t -> t.args().stream()
                    .anyMatch(FormulaParser::containsCacheForbiddenFunctions);
            case ExprNode.BlockExprNode be -> {
                boolean found = false;
                for (ExprNode.LetBinding binding : be.bindings()) {
                    if (containsCacheForbiddenFunctions(binding.value())) {
                        found = true;
                        break;
                    }
                }
                yield found || containsCacheForbiddenFunctions(be.body());
            }
            case ExprNode.BuiltinNode b -> false;
            // 校验前的 AST 不含编译形态；保守视为含（宁拒绝不误用）
            case ExprNode.SlotNode s -> true;
            case ExprNode.CompiledFuncCallNode cf -> true;
            case ExprNode.CompiledTupleCallNode ct -> true;
            case ExprNode.TupleComponentNode tc -> true;
            case ExprNode.CompiledCache2dNode c2 -> true;
            case ExprNode.CompiledCache3dNode c3 -> true;
            case ExprNode.CompiledRiverNetNode r -> true;
            case ExprNode.CompiledBlockNode cb -> true;
        };
    }

    /** 元组 let：值必须是多返回函数调用，名字个数 = 返回组件数，参数全为数值。 */
    private static void validateTupleBinding(ExprNode.LetBinding binding, List<String> errors,
                                             Map<String, ExprEvaluator.ValueType> variables, boolean biomeMode) {
        ExprNode value = binding.value();
        if (!(value instanceof ExprNode.TupleCallNode call)) {
            errors.add("Tuple let '(...)' must be bound to a multi-return function call "
                    + "(warp2 / warp3 / noise2g / worley2c); got a different expression");
            validateNode(value, errors, variables, biomeMode);
            return;
        }
        Integer arity = ExprEvaluator.multiReturnArity(call.name());
        if (arity == null) {
            errors.add("Unknown multi-return function: " + call.name());
            return;
        }
        if (arity != binding.names().size()) {
            errors.add("Tuple let binds " + binding.names().size() + " name(s) but '" + call.name()
                    + "' returns " + arity + " value(s)");
        }
        if (call.name().equals("rivernet")) {
            validateRivernetCall(call, errors, variables, biomeMode);
            return;
        }
        String msg = ExprEvaluator.validateFunction(call.name(), call.args().size());
        if (msg != null) errors.add(msg);
        for (ExprNode arg : call.args()) {
            requireNumber(validateNode(arg, errors, variables, biomeMode), "argument of " + call.name(), errors);
        }
    }

    /** climate() 的专项校验：首参必须是字段名（编译期已重写为序号），其余为数值。 */
    private static void validateClimateCall(ExprNode.FuncCallNode f, List<String> errors,
                                            Map<String, ExprEvaluator.ValueType> variables, boolean biomeMode) {
        if (f.args().size() != 3 && f.args().size() != 4) {
            errors.add("Function 'climate' expects 3 or 4 arguments (field, x, z) or (field, x, y, z), got "
                    + f.args().size());
            return;
        }
        ExprNode field = f.args().get(0);
        boolean fieldOk = field instanceof ExprNode.NumberNode n
                && n.value() == Math.rint(n.value()) && n.value() >= 0 && n.value() <= 5;
        if (!fieldOk) {
            errors.add("Function 'climate': the first argument must be a field name "
                    + "(temperature/humidity/continentalness/erosion/weirdness/depth)");
        }
        for (int i = 1; i < f.args().size(); i++) {
            requireNumber(validateNode(f.args().get(i), errors, variables, biomeMode),
                    "argument of climate", errors);
        }
    }

    /** df()/noise() 的专项校验：首参必须是带命名空间的注册名字面量（运行期再查注册表）。 */
    private static void validateRegistryCall(ExprNode.FuncCallNode f, List<String> errors,
                                             Map<String, ExprEvaluator.ValueType> variables, boolean biomeMode) {
        String arity = ExprEvaluator.validateFunction(f.name(), f.args().size());
        if (arity != null) {
            // 只报个数错误：首参是注册名不是方块，不能走通用的方块存在性校验
            errors.add(arity);
            return;
        }
        ExprNode id = f.args().get(0);
        if (!(id instanceof ExprNode.BlockNode)) {
            errors.add("Function '" + f.name() + "': the first argument must be a namespaced registry id "
                    + "(e.g. minecraft:overworld/ridges)");
        }
        for (int i = 1; i < f.args().size(); i++) {
            requireNumber(validateNode(f.args().get(i), errors, variables, biomeMode),
                    "argument of " + f.name(), errors);
        }
    }

    /** 需要原版数据的功能（M3；后续叠加模式相关功能一并加入）。 */
    private static final Set<String> VANILLA_DATA_FUNCTIONS = Set.of("climate", "df", "noise", "vheight");

    /** 表达式是否用到需要原版数据的功能（按名字查未编译调用、按编号查编译后调用）。 */
    private static boolean usesVanillaData(ExprNode node) {
        return walkFunctions(node, VANILLA_DATA_FUNCTIONS::contains,
                id -> id == ExprEvaluator.FN_CLIMATE
                        || id == ExprEvaluator.FN_DF
                        || id == ExprEvaluator.FN_NOISE
                        || id == ExprEvaluator.FN_VHEIGHT);
    }

    /** 某维度的解析结果是否用到需要原版数据的功能（方块层 / 循环层 / biome 行 / surface 行）。 */
    public static boolean usesVanillaData(ParsedDimension dimension) {
        for (Object layer : dimension.layers()) {
            if (layer instanceof FormulaLayerDef f && usesVanillaData(f.expression())) return true;
            if (layer instanceof CyclicLayerDef c) {
                for (CyclicLayerDef.Entry entry : c.entries()) {
                    if (usesVanillaData(entry.expression())) return true;
                }
            }
        }
        for (BiomeLayerDef b : dimension.biomeLayers()) {
            if (usesVanillaData(b.expression())) return true;
        }
        for (SurfaceLayerDef s : dimension.surfaceLayers()) {
            if (usesVanillaData(s.expression())) return true;
        }
        return false;
    }

    /** biome 行集合是否用到需要原版数据的功能（FormulaBiomeSource 的视图开关）。 */
    public static boolean usesVanillaDataInBiomeLines(List<BiomeLayerDef> defs) {
        for (BiomeLayerDef b : defs) {
            if (usesVanillaData(b.expression())) return true;
        }
        return false;
    }

    /** 需要原版群系分布的功能（M3.3：biome_at / vanilla 群系值）。 */
    private static final Set<String> VANILLA_BIOME_FUNCTIONS = Set.of("biome_at");

    /** 表达式是否用到原版群系分布（按名字/编号查 biome_at；vanilla 标识符编译为哨兵节点）。 */
    private static boolean usesVanillaBiome(ExprNode node) {
        return walkFunctions(node, VANILLA_BIOME_FUNCTIONS::contains, id -> id == ExprEvaluator.FN_BIOME_AT)
                || walkVanillaBuiltin(node);
    }

    /** 编译后树里是否含 vanilla 群系值哨兵节点（builtin kind 12）。 */
    private static boolean walkVanillaBuiltin(ExprNode node) {
        return switch (node) {
            case ExprNode.NumberNode ignored -> false;
            case ExprNode.VariableNode ignored -> false;
            case ExprNode.BlockNode ignored -> false;
            case ExprNode.BinaryNode b -> walkVanillaBuiltin(b.left()) || walkVanillaBuiltin(b.right());
            case ExprNode.UnaryNode u -> walkVanillaBuiltin(u.operand());
            case ExprNode.ConditionalNode c -> walkVanillaBuiltin(c.condition())
                    || walkVanillaBuiltin(c.thenExpr()) || walkVanillaBuiltin(c.elseExpr());
            case ExprNode.FuncCallNode f -> f.args().stream().anyMatch(FormulaParser::walkVanillaBuiltin);
            case ExprNode.TupleCallNode t -> t.args().stream().anyMatch(FormulaParser::walkVanillaBuiltin);
            case ExprNode.BlockExprNode be -> be.bindings().stream()
                    .anyMatch(binding -> walkVanillaBuiltin(binding.value()))
                    || walkVanillaBuiltin(be.body());
            case ExprNode.BuiltinNode b -> b.kind() == ExprEvaluator.BUILTIN_VANILLA_BIOME;
            case ExprNode.SlotNode ignored -> false;
            case ExprNode.CompiledFuncCallNode f -> f.args().stream().anyMatch(FormulaParser::walkVanillaBuiltin);
            case ExprNode.CompiledTupleCallNode t -> t.args().stream().anyMatch(FormulaParser::walkVanillaBuiltin);
            case ExprNode.TupleComponentNode ignored -> false;
            case ExprNode.CompiledCache2dNode cache -> walkVanillaBuiltin(cache.expr());
            case ExprNode.CompiledCache3dNode cache -> walkVanillaBuiltin(cache.expr());
            case ExprNode.CompiledRiverNetNode river ->
                    river.coarseExpr() != null && walkVanillaBuiltin(river.coarseExpr());
            case ExprNode.CompiledBlockNode cb -> Arrays.stream(cb.values())
                    .anyMatch(FormulaParser::walkVanillaBuiltin) || walkVanillaBuiltin(cb.body());
        };
    }

    /** biome 行集合是否用到原版群系分布（FormulaBiomeSource 的 biome_at 查询视图开关）。 */
    public static boolean usesVanillaBiomeInBiomeLines(List<BiomeLayerDef> defs) {
        for (BiomeLayerDef b : defs) {
            if (usesVanillaBiome(b.expression())) return true;
        }
        return false;
    }

    /** rivernet 的专项校验：cs/salt 为字面量、coarse 自包含且不含 ly/y 与视图/随机函数。 */
    private static void validateRivernetCall(ExprNode.TupleCallNode call, List<String> errors,
                                             Map<String, ExprEvaluator.ValueType> variables, boolean biomeMode) {
        int n = call.args().size();
        if (n != 2 && n != 3) {
            errors.add("Function 'rivernet' expects 2 or 3 arguments (cs, salt) or (coarse, cs, salt), got " + n);
            return;
        }
        int csIdx = n == 3 ? 1 : 0;
        checkLiteralInt(call.args().get(csIdx), 64, 512, errors,
                "Function 'rivernet': cs must be an integer literal in 64..512");
        if (!(call.args().get(csIdx + 1) instanceof ExprNode.NumberNode)) {
            errors.add("Function 'rivernet': salt must be a number literal");
        }
        if (n == 3) {
            ExprNode coarse = call.args().get(0);
            requireNumber(validateNode(coarse, errors, variables, biomeMode), "first argument of rivernet", errors);
            if (referencesBoundVariables(coarse, variables, new HashSet<>())) {
                errors.add("Function 'rivernet': the coarse expression must be self-contained "
                        + "(built-ins only; no let bindings or surface variables)");
            }
            if (containsCacheForbiddenFunctions(coarse)) {
                errors.add("Function 'rivernet': the coarse expression cannot use "
                        + "terrain/surfis/blockis/biomeis/rand/randexcept");
            }
            if (ExprCompiler.usesVertical(coarse, true)) {
                errors.add("Function 'rivernet': the coarse expression cannot reference ly or y");
            }
        }
    }

    /** 整数字面量范围校验（cache/rivernet 的字面量参数用）。 */
    private static void checkLiteralInt(ExprNode node, int lo, int hi, List<String> errors, String message) {
        if (!(node instanceof ExprNode.NumberNode n)
                || n.value() != Math.rint(n.value())
                || n.value() < lo || n.value() > hi) {
            errors.add(message);
        }
    }

    private static void requireNumber(ExprEvaluator.ValueType type, String location, List<String> errors) {
        if (type != ExprEvaluator.ValueType.NUMBER && type != ExprEvaluator.ValueType.UNKNOWN) {
            errors.add("Expected a number for " + location + ", got " + type);
        }
    }

    /**
     * spline/cspline 的位置参数若全是数字字面量，提前校验升序；有非常量位置时跳过
     * （运行期按"未定义但确定"处理，避免逐格求值付出校验成本）。
     */
    private static void checkAscendingPoints(String name, List<ExprNode> args, List<String> errors) {
        Double prev = null;
        for (int i = 1; i < args.size(); i += 2) {
            if (!(args.get(i) instanceof ExprNode.NumberNode n)) return;
            double p = n.value();
            if (prev != null && p <= prev) {
                errors.add("Function '" + name + "' positions must be ascending, got " + prev + " then " + p);
                return;
            }
            prev = p;
        }
    }

    private static void requireCondition(ExprEvaluator.ValueType type, String location, List<String> errors) {
        if (type != ExprEvaluator.ValueType.NUMBER && type != ExprEvaluator.ValueType.BOOLEAN
                && type != ExprEvaluator.ValueType.UNKNOWN) {
            errors.add("Expected a number or boolean for " + location + ", got " + type);
        }
    }

    /** 循环层条目（未编译；语义校验与编译由 parseLayers 统一做）。 */
    private record CyclicEntry(int thickness, ExprNode expression, boolean lyDependent) {}

    private static List<CyclicEntry> parseCyclic(String exprPart, List<ExprNode.LetBinding> shared,
                                                 Map<String, ParametricLet> macros) {
        List<CyclicEntry> entries = new ArrayList<>();
        int pos = 0;
        while (pos < exprPart.length()) {
            while (pos < exprPart.length() && (exprPart.charAt(pos) == ' ' || exprPart.charAt(pos) == ',')) {
                pos++;
            }
            if (pos >= exprPart.length()) break;

            int star = exprPart.indexOf('*', pos);
            if (star < 0) throw new IllegalArgumentException("Cyclic layer: expected '*' for thickness at position " + pos);
            int t;
            try {
                t = Integer.parseInt(exprPart.substring(pos, star).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Cyclic layer: invalid thickness \"" + exprPart.substring(pos, star).trim() + "\"");
            }
            if (t <= 0) throw new IllegalArgumentException("Cyclic layer: thickness must be positive, got " + t);
            pos = star + 1;

            if (pos >= exprPart.length() || exprPart.charAt(pos) != '[') {
                throw new IllegalArgumentException("Cyclic layer: expected '[' after '*'");
            }
            pos++;
            int depth = 1, start = pos;
            while (pos < exprPart.length() && depth > 0) {
                char c = exprPart.charAt(pos);
                if (c == '[') depth++;
                else if (c == ']') depth--;
                if (depth > 0) pos++;
            }
            if (depth != 0) throw new IllegalArgumentException("Cyclic layer: unbalanced '[' brackets");
            String inner = exprPart.substring(start, pos).trim();
            pos++;

            ExprNode expr = wrapShared(shared,
                    expandLoops(expandMacros(new ExprParser(ExprLexer.tokenize(inner)).parse(), macros)));
            // 与整层同理：依赖判定必须在编译前、且在未编译的 AST 上完成
            boolean lyDependent = ExprEvaluator.dependsOnLy(expr);
            entries.add(new CyclicEntry(t, expr, lyDependent));
        }
        return entries;
    }

    // ---------------------------------------------------------------- 共享 let

    /** 段是否是共享节级 let：`let` 后不跟标识符字符（空白 / `=` / 行尾都算）。 */
    private static boolean isSharedLet(String line) {
        if (!line.startsWith("let")) return false;
        if (line.length() == 3) return true;
        char c = line.charAt(3);
        return !(Character.isLetterOrDigit(c) || c == '_');
    }

    /** 解析出的共享 let：普通绑定与宏（参数化 let）恰有一个非空。 */
    private record SharedLet(ExprNode.LetBinding binding, ParametricLet macro) {}

    /** 参数化共享 let：编译期宏展开（调用即内联，无运行时开销）。 */
    private record ParametricLet(String name, List<String> params, ExprNode body) {}

    /** 解析 `let 名称 = 表达式`、`let 名称(参数, ...) = 表达式` 或 `let (a, b, ...) = 表达式`。 */
    private static SharedLet parseSharedLet(String line) {
        String rest = line.substring(3).trim();
        if (rest.startsWith("(")) {
            // 元组 let：let (a, b, ...) = 多返回函数(...)
            int close = rest.indexOf(')');
            if (close < 0) throw new IllegalArgumentException("missing ')' in tuple let");
            List<String> names = new ArrayList<>();
            for (String part : rest.substring(1, close).split(",")) {
                String name = part.trim();
                if (!isIdentifier(name)) {
                    throw new IllegalArgumentException("invalid name \"" + name + "\" in tuple let");
                }
                if (names.contains(name)) {
                    throw new IllegalArgumentException("duplicate name '" + name + "' in tuple let");
                }
                names.add(name);
            }
            if (names.size() < 2) {
                throw new IllegalArgumentException("tuple let requires at least two names");
            }
            String tail = rest.substring(close + 1).trim();
            if (!tail.startsWith("=")) {
                throw new IllegalArgumentException("expected '=' after tuple let names");
            }
            String valueText = tail.substring(1).trim();
            if (valueText.isEmpty()) {
                throw new IllegalArgumentException("expected an expression after 'let (...) ='");
            }
            ExprNode value = new ExprParser(ExprLexer.tokenize(valueText)).parse();
            return new SharedLet(new ExprNode.LetBinding(List.copyOf(names), value), null);
        }
        int i = 0;
        while (i < rest.length() && (Character.isLetterOrDigit(rest.charAt(i)) || rest.charAt(i) == '_')) i++;
        char first = rest.isEmpty() ? '\0' : rest.charAt(0);
        if (i == 0 || !(Character.isLetter(first) || first == '_')) {
            throw new IllegalArgumentException("expected a variable name after 'let'");
        }
        String name = rest.substring(0, i);
        boolean parens = false;
        List<String> params = List.of();
        if (i < rest.length() && rest.charAt(i) == '(') {
            parens = true;
            int close = rest.indexOf(')', i + 1);
            if (close < 0) throw new IllegalArgumentException("missing ')' in parameter list of '" + name + "'");
            String inner = rest.substring(i + 1, close).trim();
            if (!inner.isEmpty()) {
                List<String> parsed = new ArrayList<>();
                for (String part : inner.split(",")) {
                    String param = part.trim();
                    if (!isIdentifier(param)) {
                        throw new IllegalArgumentException("invalid parameter \"" + param + "\" of '" + name + "'");
                    }
                    if (parsed.contains(param)) {
                        throw new IllegalArgumentException("duplicate parameter '" + param + "' of '" + name + "'");
                    }
                    parsed.add(param);
                }
                params = List.copyOf(parsed);
            }
            i = close + 1;
        }
        String tail = rest.substring(i).trim();
        if (!tail.startsWith("=")) {
            throw new IllegalArgumentException("expected '=' after 'let " + name + "'");
        }
        String valueText = tail.substring(1).trim();
        if (valueText.isEmpty()) {
            throw new IllegalArgumentException("expected an expression after 'let " + name + " '");
        }
        ExprNode value = new ExprParser(ExprLexer.tokenize(valueText)).parse();
        if (!parens) {
            return new SharedLet(new ExprNode.LetBinding(name, value), null);
        }
        if (ExprEvaluator.isFunctionName(name)) {
            throw new IllegalArgumentException("shared let '" + name + "' collides with a built-in function");
        }
        return new SharedLet(null, new ParametricLet(name, params, value));
    }

    /** 标识符：字母/下划线开头，后跟字母数字下划线。 */
    private static boolean isIdentifier(String s) {
        if (s.isEmpty()) return false;
        char c0 = s.charAt(0);
        if (!(Character.isLetter(c0) || c0 == '_')) return false;
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_')) return false;
        }
        return true;
    }

    /**
     * 宏展开：把参数化 let 的调用点替换为函数体（实参表达式代入参数）。
     * 展开在编译前完成，因此没有运行时开销；自引用会在深度上限处报错。
     */
    private static ExprNode expandMacros(ExprNode node, Map<String, ParametricLet> macros) {
        return expandMacros(node, macros, 0);
    }

    private static ExprNode expandMacros(ExprNode node, Map<String, ParametricLet> macros, int depth) {
        if (depth > 64) {
            throw new IllegalArgumentException("parametric let expansion is too deep (self reference?)");
        }
        return switch (node) {
            case ExprNode.NumberNode n -> n;
            case ExprNode.VariableNode v -> v;
            case ExprNode.BlockNode b -> b;
            case ExprNode.BinaryNode b -> new ExprNode.BinaryNode(
                    expandMacros(b.left(), macros, depth), b.op(), expandMacros(b.right(), macros, depth));
            case ExprNode.UnaryNode u -> new ExprNode.UnaryNode(u.op(), expandMacros(u.operand(), macros, depth));
            case ExprNode.ConditionalNode c -> new ExprNode.ConditionalNode(
                    expandMacros(c.condition(), macros, depth),
                    expandMacros(c.thenExpr(), macros, depth),
                    expandMacros(c.elseExpr(), macros, depth));
            case ExprNode.FuncCallNode f -> {
                ParametricLet macro = macros.get(f.name());
                if (macro == null) {
                    List<ExprNode> args = new ArrayList<>(f.args().size());
                    for (ExprNode arg : f.args()) args.add(expandMacros(arg, macros, depth));
                    yield new ExprNode.FuncCallNode(f.name(), args);
                }
                if (f.args().size() != macro.params().size()) {
                    throw new IllegalArgumentException("parametric let '" + f.name() + "' expects "
                            + macro.params().size() + " argument(s), got " + f.args().size());
                }
                List<ExprNode> args = new ArrayList<>(f.args().size());
                for (ExprNode arg : f.args()) args.add(expandMacros(arg, macros, depth));
                yield expandMacros(substitute(macro.body(), macro.params(), args), macros, depth + 1);
            }
            case ExprNode.BlockExprNode be -> {
                List<ExprNode.LetBinding> bindings = new ArrayList<>(be.bindings().size());
                for (ExprNode.LetBinding binding : be.bindings()) {
                    bindings.add(new ExprNode.LetBinding(binding.names(),
                            expandMacros(binding.value(), macros, depth)));
                }
                yield new ExprNode.BlockExprNode(bindings, expandMacros(be.body(), macros, depth));
            }
            case ExprNode.TupleCallNode t -> {
                List<ExprNode> args = new ArrayList<>(t.args().size());
                for (ExprNode arg : t.args()) args.add(expandMacros(arg, macros, depth));
                yield new ExprNode.TupleCallNode(t.name(), args);
            }
            case ExprNode.CompiledTupleCallNode t -> t;
            case ExprNode.TupleComponentNode t -> t;
            case ExprNode.CompiledCache2dNode c -> c;
            case ExprNode.CompiledCache3dNode c -> c;
            case ExprNode.CompiledRiverNetNode r -> r;
            case ExprNode.BuiltinNode b -> b;
            case ExprNode.SlotNode s -> s;
            case ExprNode.CompiledFuncCallNode cf -> cf;
            case ExprNode.CompiledBlockNode cb -> cb;
        };
    }

    /** 宏体代入：参数名替换为实参表达式；块内同名绑定会遮蔽参数（与求值作用域一致）。 */
    private static ExprNode substitute(ExprNode node, List<String> params, List<ExprNode> args) {
        Map<String, ExprNode> subs = new HashMap<>();
        for (int i = 0; i < params.size(); i++) subs.put(params.get(i), args.get(i));
        return substitute(node, subs);
    }

    // ---------------------------------------------------------------- 循环 / 位移宏（1.3.0）

    /**
     * 循环与位移宏的编译期展开（在宏展开之后、语义校验之前进行）：
     *
     * <ul>
     *   <li>{@code sum(k, a, b, expr)} / {@code min(...)} / {@code max(...)}（循环形式）：
     *       k 是循环变量名，a、b 为整数字面量；展开为逐项表达式（加法链 / 多参 min·max）。
     *       嵌套迭代总数（各层循环次数之积）不超过 64。</li>
     *   <li>{@code shift(expr, dx, dz)}：把 expr 中出现的 x、z 替换为 x+dx、z+dz；
     *       dx/dz 里的 x、z 保持原样（在原点求值）。</li>
     * </ul>
     */
    public static ExprNode expandLoops(ExprNode node) {
        // 展开循环/位移宏之后，把 climate(字段名, …) 的首参重写为字段序号
        return rewriteClimateFields(expandLoops(node, 1));
    }

    /** climate() 的字段名 → 序号（与 {@code ExprEvaluator.VanillaView} 的字段码一致）。 */
    private static Integer climateFieldCode(String name) {
        return ExprEvaluator.climateFieldCode(name);
    }

    /** 把 {@code climate(字段名, …)} 的首参重写为字段序号（考虑 let 遮蔽；不引入新内建）。 */
    private static ExprNode rewriteClimateFields(ExprNode node) {
        return rewriteClimateFields(node, new HashSet<>());
    }

    private static ExprNode rewriteClimateFields(ExprNode node, Set<String> shadowed) {
        return switch (node) {
            case ExprNode.NumberNode n -> n;
            case ExprNode.BlockNode b -> b;
            case ExprNode.VariableNode v -> v;
            case ExprNode.BinaryNode b -> new ExprNode.BinaryNode(
                    rewriteClimateFields(b.left(), shadowed), b.op(),
                    rewriteClimateFields(b.right(), shadowed));
            case ExprNode.UnaryNode u -> new ExprNode.UnaryNode(
                    u.op(), rewriteClimateFields(u.operand(), shadowed));
            case ExprNode.ConditionalNode c -> new ExprNode.ConditionalNode(
                    rewriteClimateFields(c.condition(), shadowed),
                    rewriteClimateFields(c.thenExpr(), shadowed),
                    rewriteClimateFields(c.elseExpr(), shadowed));
            case ExprNode.FuncCallNode f -> {
                List<ExprNode> args = new ArrayList<>(f.args().size());
                for (int i = 0; i < f.args().size(); i++) {
                    ExprNode arg = f.args().get(i);
                    if (f.name().equals("climate") && i == 0
                            && arg instanceof ExprNode.VariableNode v && !shadowed.contains(v.name())) {
                        Integer code = climateFieldCode(v.name());
                        args.add(code == null ? arg : new ExprNode.NumberNode(code));
                    } else {
                        args.add(rewriteClimateFields(arg, shadowed));
                    }
                }
                yield new ExprNode.FuncCallNode(f.name(), args);
            }
            case ExprNode.TupleCallNode t -> {
                List<ExprNode> args = new ArrayList<>(t.args().size());
                for (ExprNode arg : t.args()) args.add(rewriteClimateFields(arg, shadowed));
                yield new ExprNode.TupleCallNode(t.name(), args);
            }
            case ExprNode.BlockExprNode be -> {
                Set<String> inner = new HashSet<>(shadowed);
                List<ExprNode.LetBinding> bindings = new ArrayList<>(be.bindings().size());
                for (ExprNode.LetBinding binding : be.bindings()) {
                    bindings.add(new ExprNode.LetBinding(binding.names(),
                            rewriteClimateFields(binding.value(), inner)));
                    inner.addAll(binding.names());
                }
                yield new ExprNode.BlockExprNode(bindings, rewriteClimateFields(be.body(), inner));
            }
            case ExprNode.BuiltinNode b -> b;
            case ExprNode.SlotNode s -> s;
            case ExprNode.CompiledFuncCallNode cf -> cf;
            case ExprNode.CompiledTupleCallNode ct -> ct;
            case ExprNode.TupleComponentNode tc -> tc;
            case ExprNode.CompiledCache2dNode c2 -> c2;
            case ExprNode.CompiledCache3dNode c3 -> c3;
            case ExprNode.CompiledRiverNetNode r -> r;
            case ExprNode.CompiledBlockNode cb -> cb;
        };
    }

    private static ExprNode expandLoops(ExprNode node, int multiplier) {
        return switch (node) {
            case ExprNode.NumberNode n -> n;
            case ExprNode.VariableNode v -> v;
            case ExprNode.BlockNode b -> b;
            case ExprNode.BinaryNode b -> new ExprNode.BinaryNode(
                    expandLoops(b.left(), multiplier), b.op(), expandLoops(b.right(), multiplier));
            case ExprNode.UnaryNode u -> new ExprNode.UnaryNode(u.op(), expandLoops(u.operand(), multiplier));
            case ExprNode.ConditionalNode c -> new ExprNode.ConditionalNode(
                    expandLoops(c.condition(), multiplier),
                    expandLoops(c.thenExpr(), multiplier),
                    expandLoops(c.elseExpr(), multiplier));
            case ExprNode.FuncCallNode f -> {
                if (f.name().equals("sum") || isLoopForm(f)) {
                    yield expandLoopCall(f, multiplier);
                }
                if (f.name().equals("shift") && f.args().size() == 3) {
                    yield expandShift(f, multiplier);
                }
                List<ExprNode> args = new ArrayList<>(f.args().size());
                for (ExprNode arg : f.args()) args.add(expandLoops(arg, multiplier));
                yield new ExprNode.FuncCallNode(f.name(), args);
            }
            case ExprNode.TupleCallNode t -> {
                List<ExprNode> args = new ArrayList<>(t.args().size());
                for (ExprNode arg : t.args()) args.add(expandLoops(arg, multiplier));
                yield new ExprNode.TupleCallNode(t.name(), args);
            }
            case ExprNode.BlockExprNode be -> {
                List<ExprNode.LetBinding> bindings = new ArrayList<>(be.bindings().size());
                for (ExprNode.LetBinding binding : be.bindings()) {
                    bindings.add(new ExprNode.LetBinding(binding.names(),
                            expandLoops(binding.value(), multiplier)));
                }
                yield new ExprNode.BlockExprNode(bindings, expandLoops(be.body(), multiplier));
            }
            case ExprNode.BuiltinNode b -> b;
            case ExprNode.SlotNode s -> s;
            case ExprNode.CompiledFuncCallNode cf -> cf;
            case ExprNode.CompiledTupleCallNode ct -> ct;
            case ExprNode.TupleComponentNode tc -> tc;
            case ExprNode.CompiledCache2dNode c2 -> c2;
            case ExprNode.CompiledCache3dNode c3 -> c3;
            case ExprNode.CompiledRiverNetNode r -> r;
            case ExprNode.CompiledBlockNode cb -> cb;
        };
    }

    /** min/max 的循环形式：4 参且首参不是内建变量名（a、b 的整数性在展开时报错说明）。 */
    private static boolean isLoopForm(ExprNode.FuncCallNode f) {
        if (!f.name().equals("min") && !f.name().equals("max")) return false;
        if (f.args().size() != 4) return false;
        if (!(f.args().get(0) instanceof ExprNode.VariableNode v)) return false;
        return !KNOWN_VARS.contains(v.name());
    }

    private static ExprNode expandLoopCall(ExprNode.FuncCallNode f, int multiplier) {
        if (f.args().size() != 4) {
            throw new IllegalArgumentException(f.name() + "(k, a, b, expr) expects 4 arguments, got " + f.args().size());
        }
        if (!(f.args().get(0) instanceof ExprNode.VariableNode loopVar) || KNOWN_VARS.contains(loopVar.name())) {
            throw new IllegalArgumentException(f.name() + "(k, a, b, expr): the first argument must be a loop variable name");
        }
        Integer a = intLiteral(f.args().get(1));
        Integer b = intLiteral(f.args().get(2));
        if (a == null || b == null) {
            throw new IllegalArgumentException(f.name() + "(k, a, b, expr): a and b must be integer literals");
        }
        if (b < a) {
            throw new IllegalArgumentException(f.name() + ": empty loop range " + a + ".." + b);
        }
        long count = (long) b - a + 1;
        if (count > 64 || (long) multiplier * count > 64) {
            throw new IllegalArgumentException(f.name() + ": loop iterations exceed 64 (nested total)");
        }
        List<ExprNode> expanded = new ArrayList<>((int) count);
        Map<String, ExprNode> subs = new HashMap<>();
        for (int i = a; i <= b; i++) {
            subs.clear();
            subs.put(loopVar.name(), new ExprNode.NumberNode(i));
            expanded.add(expandLoops(substitute(f.args().get(3), subs), multiplier * (int) count));
        }
        if (f.name().equals("sum")) {
            ExprNode total = expanded.get(0);
            for (int i = 1; i < expanded.size(); i++) {
                total = new ExprNode.BinaryNode(total, ExprNode.BinaryOp.ADD, expanded.get(i));
            }
            return total;
        }
        return new ExprNode.FuncCallNode(f.name(), expanded);
    }

    private static ExprNode expandShift(ExprNode.FuncCallNode f, int multiplier) {
        ExprNode target = expandLoops(f.args().get(0), multiplier);
        ExprNode dx = expandLoops(f.args().get(1), multiplier);
        ExprNode dz = expandLoops(f.args().get(2), multiplier);
        Map<String, ExprNode> subs = new HashMap<>();
        subs.put("x", new ExprNode.BinaryNode(new ExprNode.VariableNode("x"), ExprNode.BinaryOp.ADD, dx));
        subs.put("z", new ExprNode.BinaryNode(new ExprNode.VariableNode("z"), ExprNode.BinaryOp.ADD, dz));
        return substitute(target, subs);
    }

    /** 整数数字字面量（含负数字面量，求值为整数值的 NumberNode 也算）。 */
    private static Integer intLiteral(ExprNode node) {
        if (node instanceof ExprNode.NumberNode n
                && n.value() == Math.rint(n.value())
                && n.value() >= Integer.MIN_VALUE && n.value() <= Integer.MAX_VALUE) {
            return (int) n.value();
        }
        return null;
    }

    private static ExprNode substitute(ExprNode node, Map<String, ExprNode> subs) {
        return switch (node) {
            case ExprNode.NumberNode n -> n;
            case ExprNode.VariableNode v -> subs.getOrDefault(v.name(), v);
            case ExprNode.BlockNode b -> b;
            case ExprNode.BinaryNode b -> new ExprNode.BinaryNode(
                    substitute(b.left(), subs), b.op(), substitute(b.right(), subs));
            case ExprNode.UnaryNode u -> new ExprNode.UnaryNode(u.op(), substitute(u.operand(), subs));
            case ExprNode.ConditionalNode c -> new ExprNode.ConditionalNode(
                    substitute(c.condition(), subs),
                    substitute(c.thenExpr(), subs), substitute(c.elseExpr(), subs));
            case ExprNode.FuncCallNode f -> {
                List<ExprNode> args = new ArrayList<>(f.args().size());
                for (ExprNode arg : f.args()) args.add(substitute(arg, subs));
                yield new ExprNode.FuncCallNode(f.name(), args);
            }
            case ExprNode.BlockExprNode be -> {
                Map<String, ExprNode> inner = new HashMap<>(subs);
                List<ExprNode.LetBinding> bindings = new ArrayList<>(be.bindings().size());
                for (ExprNode.LetBinding binding : be.bindings()) {
                    // 绑定值先于绑定名可见（与求值器一致）
                    bindings.add(new ExprNode.LetBinding(binding.names(), substitute(binding.value(), inner)));
                    for (String name : binding.names()) inner.remove(name);
                }
                yield new ExprNode.BlockExprNode(bindings, substitute(be.body(), inner));
            }
            case ExprNode.TupleCallNode t -> {
                List<ExprNode> args = new ArrayList<>(t.args().size());
                for (ExprNode arg : t.args()) args.add(substitute(arg, subs));
                yield new ExprNode.TupleCallNode(t.name(), args);
            }
            case ExprNode.CompiledTupleCallNode t -> t;
            case ExprNode.TupleComponentNode t -> t;
            case ExprNode.CompiledCache2dNode c -> c;
            case ExprNode.CompiledCache3dNode c -> c;
            case ExprNode.CompiledRiverNetNode r -> r;
            case ExprNode.BuiltinNode b -> b;
            case ExprNode.SlotNode s -> s;
            case ExprNode.CompiledFuncCallNode cf -> cf;
            case ExprNode.CompiledBlockNode cb -> cb;
        };
    }

    /** 把共享绑定前置到层表达式外：{ 共享绑定...; 原表达式 }。 */
    private static ExprNode wrapShared(List<ExprNode.LetBinding> shared, ExprNode body) {
        if (shared.isEmpty()) return body;
        return new ExprNode.BlockExprNode(List.copyOf(shared), body);
    }

    private static String[] smartSplit(String input) {
        List<String> parts = new ArrayList<>();
        int start = 0, depth = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            else if (c == ';' && depth == 0) {
                parts.add(input.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(input.substring(start));
        return parts.toArray(new String[0]);
    }

    private static int findColon(String line) {
        int balance = 0;
        boolean inTernary = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '(') balance++;
            else if (c == ')') balance--;
            else if (c == '?' && balance == 0) inTernary = true;
            else if (c == ':' && balance == 0 && !inTernary) return i;
        }
        return -1;
    }
}
