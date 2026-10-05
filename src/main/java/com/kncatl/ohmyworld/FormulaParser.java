package com.kncatl.ohmyworld;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.kncatl.ohmyworld.expr.BlockResolver;
import com.kncatl.ohmyworld.expr.ExprCompiler;
import com.kncatl.ohmyworld.expr.ExprEvaluator;
import com.kncatl.ohmyworld.expr.ExprLexer;
import com.kncatl.ohmyworld.expr.ExprNode;
import com.kncatl.ohmyworld.expr.ExprParser;

public class FormulaParser {

    private static final List<String> KNOWN_VARS = List.of("x", "z", "ly", "seed");
    // These are deliberately high safety ceilings, not a formula complexity budget.
    public static final int MAX_INPUT_LENGTH = 1_048_576;
    private static final int MAX_LAYERS = 65_536;

    public record ParseResult(List<Object> layers, List<String> errors) {}

    /** 维度节的规范名（{@link DimensionParseResult} 与 PatternData 的键）。 */
    public static final String DIM_OVERWORLD = "overworld";
    public static final String DIM_NETHER = "the_nether";
    public static final String DIM_END = "the_end";

    /** 解析出的单个维度：层表 + 可选指令（别名与目标共享同一实例）。 */
    public record ParsedDimension(List<Object> layers, DimensionRules.StructureRule structure,
                                  DimensionRules.BiomeRule biome, boolean featuresOff) {}

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
        Map<String, ParsedDimension> dimensions = new LinkedHashMap<>();
        if (result.errors().isEmpty() && !result.layers().isEmpty()) {
            dimensions.put(DIM_OVERWORLD, new ParsedDimension(result.layers(),
                    DimensionRules.StructureRule.ALL, null, false));
        }
        return new DimensionParseResult(Map.copyOf(dimensions), result.errors(), false);
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
        boolean featuresOff = false;
        boolean structureSeen = false;
        boolean biomeSeen = false;
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
                        + "] (available: structure, biome, features)");
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
        if (result.errors().isEmpty() && !result.layers().isEmpty()) {
            dimensions.put(name, new ParsedDimension(result.layers(), structure, biome, featuresOff));
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
        if (!normalized.contains(":") && !KNOWN_BIOME_NAMES.contains(normalized)) {
            errors.add(dimension + ": unknown biome \"" + a + "\" (see the guide for available names)");
            return null;
        }
        return DimensionRules.BiomeRule.single(normalized);
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

    /** 旧入口的层解析主体（含 smartSplit 分段与逐层校验）。 */
    private static ParseResult parseLayers(String cleaned) {
        List<Object> layers = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        String[] lines = smartSplit(cleaned);
        for (int lineIdx = 0; lineIdx < lines.length; lineIdx++) {
            if (layers.size() + errors.size() >= MAX_LAYERS) {
                errors.add("Formula contains too many layers; maximum is " + MAX_LAYERS);
                break;
            }
            String line = lines[lineIdx].trim();
            if (line.isEmpty()) continue;

            try {
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
                int yStart, yEnd;
                int dotsIdx = valuePart.indexOf("..");
                if (dotsIdx >= 0) {
                    yStart = Integer.parseInt(valuePart.substring(0, dotsIdx).trim());
                    yEnd = Integer.parseInt(valuePart.substring(dotsIdx + 2).trim());
                } else {
                    yStart = yEnd = Integer.parseInt(valuePart);
                }
                if (yStart > yEnd) {
                    errors.add(layerError(lineIdx, "range start " + yStart + " is greater than end " + yEnd, line));
                    continue;
                }

                if (exprPart.contains("*[")) {
                    List<CyclicLayerDef.Entry> entries = parseCyclic(exprPart);
                    if (entries.isEmpty()) {
                        errors.add(layerError(lineIdx, "cyclic layer has no valid entries", line));
                        continue;
                    }
                    List<String> valErrors = new ArrayList<>();
                    for (CyclicLayerDef.Entry e : entries) {
                        ExprEvaluator.ValueType type = validateNode(e.expression(), valErrors, new HashMap<>());
                        if (type != ExprEvaluator.ValueType.BLOCK && type != ExprEvaluator.ValueType.UNKNOWN) {
                            valErrors.add("Cyclic layer expression must return a block, got " + type);
                        }
                    }
                    if (!valErrors.isEmpty()) {
                        for (String ve : valErrors) errors.add(layerError(lineIdx, ve, line));
                        continue;
                    }
                    layers.add(new CyclicLayerDef(yStart, yEnd, entries));
                } else {
                    ExprNode expr = new ExprParser(ExprLexer.tokenize(exprPart)).parse();
                    List<String> valErrors = new ArrayList<>();
                    ExprEvaluator.ValueType type = validateNode(expr, valErrors, new HashMap<>());
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
        if (layers.isEmpty() && errors.isEmpty()) errors.add("Formula contains no layers");
        return new ParseResult(List.copyOf(layers), List.copyOf(errors));
    }

    public static List<Object> parse(String input) {
        ParseResult result = parseWithErrors(input);
        if (!result.errors().isEmpty()) throw new IllegalArgumentException(String.join("; ", result.errors()));
        return result.layers();
    }

    private static ParseResult invalid(String error) {
        return new ParseResult(List.of(), List.of(error));
    }

    private static String layerError(int lineIdx, String msg, String line) {
        return "Layer " + (lineIdx + 1) + ": " + msg + " in \"" + truncate(line) + "\"";
    }

    private static String truncate(String s) {
        return s.length() <= 60 ? s : s.substring(0, 57) + "...";
    }

    /** 语义校验：同时推导表达式类型，避免非法结果在运行时静默变成空气。 */
    private static ExprEvaluator.ValueType validateNode(ExprNode node, List<String> errors,
                                                        Map<String, ExprEvaluator.ValueType> variables) {
        switch (node) {
            case ExprNode.NumberNode n -> { return ExprEvaluator.ValueType.NUMBER; }
            case ExprNode.VariableNode v -> {
                if (variables.containsKey(v.name())) return variables.get(v.name());
                if (KNOWN_VARS.contains(v.name())) return ExprEvaluator.ValueType.NUMBER;
                errors.add("Unknown variable: " + v.name() + " (available: x, z, ly, seed)");
                return ExprEvaluator.ValueType.UNKNOWN;
            }
            case ExprNode.BlockNode b -> {
                if (!BlockResolver.exists(b.blockId())) {
                    errors.add("Unknown block: " + b.blockId());
                    return ExprEvaluator.ValueType.UNKNOWN;
                }
                return ExprEvaluator.ValueType.BLOCK;
            }
            case ExprNode.BinaryNode bn -> {
                ExprEvaluator.ValueType left = validateNode(bn.left(), errors, variables);
                ExprEvaluator.ValueType right = validateNode(bn.right(), errors, variables);
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
                ExprEvaluator.ValueType operand = validateNode(u.operand(), errors, variables);
                if (u.op() == ExprNode.UnaryOp.NOT) {
                    requireCondition(operand, "operand of !", errors);
                    return ExprEvaluator.ValueType.BOOLEAN;
                }
                requireNumber(operand, "operand of unary -", errors);
                return ExprEvaluator.ValueType.NUMBER;
            }
            case ExprNode.ConditionalNode c -> {
                ExprEvaluator.ValueType condition = validateNode(c.condition(), errors, variables);
                requireCondition(condition, "ternary condition", errors);
                ExprEvaluator.ValueType thenType = validateNode(c.thenExpr(), errors, variables);
                ExprEvaluator.ValueType elseType = validateNode(c.elseExpr(), errors, variables);
                if (thenType != ExprEvaluator.ValueType.UNKNOWN && elseType != ExprEvaluator.ValueType.UNKNOWN && thenType != elseType) {
                    errors.add("Ternary branches must return the same type, got " + thenType + " and " + elseType);
                    return ExprEvaluator.ValueType.UNKNOWN;
                }
                return thenType == ExprEvaluator.ValueType.UNKNOWN ? elseType : thenType;
            }
            case ExprNode.FuncCallNode f -> {
                String msg = ExprEvaluator.validateFunction(f.name(), f.args().size());
                if (msg != null) {
                    errors.add(msg);
                    for (ExprNode a : f.args()) validateNode(a, errors, variables);
                    return ExprEvaluator.ValueType.UNKNOWN;
                }
                if (f.name().equals("rand") || f.name().equals("randexcept")) {
                    for (ExprNode a : f.args()) {
                        ExprEvaluator.ValueType type = validateNode(a, errors, variables);
                        if (type != ExprEvaluator.ValueType.BLOCK && type != ExprEvaluator.ValueType.UNKNOWN) {
                            errors.add("Function '" + f.name() + "' expects block arguments, got " + type);
                        }
                    }
                    return ExprEvaluator.ValueType.BLOCK;
                }
                for (ExprNode a : f.args()) {
                    ExprEvaluator.ValueType type = validateNode(a, errors, variables);
                    requireNumber(type, "argument of " + f.name(), errors);
                }
                return ExprEvaluator.ValueType.NUMBER;
            }
            case ExprNode.BlockExprNode be -> {
                Map<String, ExprEvaluator.ValueType> local = new HashMap<>(variables);
                for (ExprNode.LetBinding lb : be.bindings()) {
                    local.put(lb.name(), validateNode(lb.value(), errors, local));
                }
                return validateNode(be.body(), errors, local);
            }
            // 以下是编译后的形态。语义校验发生在编译之前（见本文件的处理顺序），
            // 因此这几个分支实际不会走到；这里只是为了让 switch 穷尽。
            case ExprNode.BuiltinNode b -> { return ExprEvaluator.ValueType.NUMBER; }
            case ExprNode.SlotNode s -> { return ExprEvaluator.ValueType.UNKNOWN; }
            case ExprNode.CompiledFuncCallNode cf -> { return ExprEvaluator.ValueType.UNKNOWN; }
            case ExprNode.CompiledBlockNode cb -> { return ExprEvaluator.ValueType.UNKNOWN; }
        }
    }

    private static void requireNumber(ExprEvaluator.ValueType type, String location, List<String> errors) {
        if (type != ExprEvaluator.ValueType.NUMBER && type != ExprEvaluator.ValueType.UNKNOWN) {
            errors.add("Expected a number for " + location + ", got " + type);
        }
    }

    private static void requireCondition(ExprEvaluator.ValueType type, String location, List<String> errors) {
        if (type != ExprEvaluator.ValueType.NUMBER && type != ExprEvaluator.ValueType.BOOLEAN
                && type != ExprEvaluator.ValueType.UNKNOWN) {
            errors.add("Expected a number or boolean for " + location + ", got " + type);
        }
    }

    private static List<CyclicLayerDef.Entry> parseCyclic(String exprPart) {
        List<CyclicLayerDef.Entry> entries = new ArrayList<>();
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

            ExprNode expr = new ExprParser(ExprLexer.tokenize(inner)).parse();
            // 与整层同理：依赖判定必须在编译前、且在未编译的 AST 上完成
            boolean lyDependent = ExprEvaluator.dependsOnLy(expr);
            entries.add(new CyclicLayerDef.Entry(t, ExprCompiler.compile(expr), lyDependent));
        }
        return entries;
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
