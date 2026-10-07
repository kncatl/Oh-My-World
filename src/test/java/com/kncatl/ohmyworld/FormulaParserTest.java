package com.kncatl.ohmyworld;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FormulaParser} 的语法/校验契约。
 *
 * <p>纯 JUnit 环境没有 Minecraft 注册表引导：任何方块字面量都会让解析抛
 * {@code NoClassDefFoundError}（`Blocks`/`BuiltInRegistries` 初始化失败，见
 * SESSION-HANDOVER 的踩坑记录与冒烟纪律）。因此这里全部使用"数值结果"的表达式：
 * 语义校验仍会走完整的变量/函数识别，只是最后报"图层必须返回方块"。
 * 需要"解析成功"的用例用 {@code rand()}（合法但零方块解析依赖）替代方块字面量。
 */
class FormulaParserTest {

    /** 断言「唯一错误是图层必须返回方块」——即表达式里的变量/函数本身都被正确识别。 */
    private static void assertOnlyReturnsBlockTypeError(String input) {
        FormulaParser.ParseResult result = FormulaParser.parseWithErrors(input);
        assertEquals(1, result.errors().size(), input + " -> " + result.errors());
        assertTrue(result.errors().get(0).contains("must return a block"),
                input + " -> " + result.errors());
        assertEquals(0, result.layers().size(), input);
    }

    @Test
    void seedIsRecognizedAsAVariable() {
        assertOnlyReturnsBlockTypeError("y=0: seed");
        assertOnlyReturnsBlockTypeError("y=0: seed > 0");
        assertOnlyReturnsBlockTypeError("y=0: { let seed = 7; seed }"); // let 遮蔽内建 seed
    }

    @Test
    void seedhashIsRecognizedAsAFunction() {
        assertOnlyReturnsBlockTypeError("y=0: seedhash()");
        assertOnlyReturnsBlockTypeError("y=0: seedhash(x, z, 0)");
        assertOnlyReturnsBlockTypeError("y=0: { let h = seedhash(x); h }");
    }

    @Test
    void seedhashRejectsNonNumberArguments() {
        FormulaParser.ParseResult result = FormulaParser.parseWithErrors("y=0: seedhash(x > 0)");
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("argument of seedhash")),
                result.errors().toString());
    }

    @Test
    void unknownNamesStillReportCorrectly() {
        FormulaParser.ParseResult variable = FormulaParser.parseWithErrors("y=0: seeds");
        assertEquals(1, variable.errors().size(), variable.errors().toString());
        assertTrue(variable.errors().get(0).contains("available: x, y, z, ly, seed"),
                variable.errors().toString());

        FormulaParser.ParseResult function = FormulaParser.parseWithErrors("y=0: seedhashes(x)");
        assertEquals(1, function.errors().size(), function.errors().toString());
        assertTrue(function.errors().get(0).contains("Unknown function"),
                function.errors().toString());
    }

    // ── 分节语法（多维度，P3） ──────────────────────────────────────────────

    /** 正例：单节公式解析成功（rand() 不依赖方块注册表，可用于纯 JUnit 正例）。 */
    @Test
    void sectionedFormulaWithOneDimensionParses() {
        FormulaParser.DimensionParseResult result =
                FormulaParser.parseDimensionsWithErrors("{overworld=y=0: rand()}");
        assertTrue(result.sectioned(), result.errors().toString());
        assertTrue(result.errors().isEmpty(), result.errors().toString());
        assertEquals(List.of(FormulaParser.DIM_OVERWORLD), List.copyOf(result.dimensions().keySet()));
        assertEquals(1, result.dimensions().get(FormulaParser.DIM_OVERWORLD).layers().size());
    }

    /** 名称：简写（nether/end）与 minecraft: 前缀都归一到规范名。 */
    @Test
    void dimensionNamesAreCanonicalized() {
        FormulaParser.DimensionParseResult result = FormulaParser.parseDimensionsWithErrors(
                "{minecraft:nether=y=0: rand()}{end=y=0: rand()}");
        assertTrue(result.errors().isEmpty(), result.errors().toString());
        assertTrue(result.dimensions().containsKey(FormulaParser.DIM_NETHER));
        assertTrue(result.dimensions().containsKey(FormulaParser.DIM_END));
    }

    /** 别名：链式解析到显式定义的维度，且与目标共享同一份层表。 */
    @Test
    void aliasSharesTheTargetLayerTable() {
        FormulaParser.DimensionParseResult result = FormulaParser.parseDimensionsWithErrors(
                "{overworld=y=0: rand()}{the_nether=overworld}{the_end=nether}");
        assertTrue(result.errors().isEmpty(), result.errors().toString());
        assertEquals(3, result.dimensions().size());
        assertSame(result.dimensions().get(FormulaParser.DIM_OVERWORLD),
                result.dimensions().get(FormulaParser.DIM_NETHER));
        assertSame(result.dimensions().get(FormulaParser.DIM_OVERWORLD),
                result.dimensions().get(FormulaParser.DIM_END));
    }

    /** 旧输入（不带 {}）按"仅 overworld"解析，行为与旧版一致。 */
    @Test
    void legacyInputParsesAsOverworldOnly() {
        FormulaParser.DimensionParseResult result = FormulaParser.parseDimensionsWithErrors("y=0: rand()");
        assertFalse(result.sectioned());
        assertTrue(result.errors().isEmpty(), result.errors().toString());
        assertEquals(1, result.dimensions().size());
        assertTrue(result.dimensions().containsKey(FormulaParser.DIM_OVERWORLD));
    }

    /** 节内错误带维度前缀（如 the_nether: Layer 1: ...）。 */
    @Test
    void sectionErrorsCarryTheDimensionPrefix() {
        FormulaParser.DimensionParseResult result = FormulaParser.parseDimensionsWithErrors(
                "{overworld=y=0: 1}{the_nether=y=0: 2}");
        assertEquals(2, result.errors().size(), result.errors().toString());
        assertTrue(result.errors().get(0).startsWith("overworld: Layer 1: "), result.errors().toString());
        assertTrue(result.errors().get(1).startsWith("the_nether: Layer 1: "), result.errors().toString());
        assertTrue(result.dimensions().isEmpty());
    }

    /** 未知名 / 重复节 / 空节 / 括号不配对 / 节外杂项文本 → 逐条报错。 */
    @Test
    void malformedSectionsAreRejected() {
        FormulaParser.DimensionParseResult unknown =
                FormulaParser.parseDimensionsWithErrors("{foo=y=0: rand()}");
        assertTrue(unknown.errors().stream().anyMatch(e -> e.contains("Unknown dimension \"foo\"")),
                unknown.errors().toString());

        FormulaParser.DimensionParseResult duplicate = FormulaParser.parseDimensionsWithErrors(
                "{overworld=y=0: rand()}{minecraft:overworld=y=1: rand()}");
        assertTrue(duplicate.errors().stream().anyMatch(e -> e.contains("duplicate dimension section")),
                duplicate.errors().toString());

        FormulaParser.DimensionParseResult empty = FormulaParser.parseDimensionsWithErrors("{the_nether=}");
        assertTrue(empty.errors().stream().anyMatch(e -> e.contains("empty")), empty.errors().toString());

        FormulaParser.DimensionParseResult unbalanced =
                FormulaParser.parseDimensionsWithErrors("{overworld=y=0: rand()");
        assertTrue(unbalanced.errors().stream().anyMatch(e -> e.contains("Unbalanced")),
                unbalanced.errors().toString());

        FormulaParser.DimensionParseResult trailing = FormulaParser.parseDimensionsWithErrors(
                "{overworld=y=0: rand()} leftOver");
        assertTrue(trailing.errors().stream().anyMatch(e -> e.contains("outside dimension sections")),
                trailing.errors().toString());
    }

    /** 别名：目标必须在本输入里显式定义；环路 / 自引用报错。 */
    @Test
    void aliasErrorsAreReported() {
        FormulaParser.DimensionParseResult dangling = FormulaParser.parseDimensionsWithErrors("{the_end=overworld}");
        assertTrue(dangling.errors().stream().anyMatch(e -> e.contains("alias target \"overworld\" is not defined")),
                dangling.errors().toString());

        FormulaParser.DimensionParseResult cycle = FormulaParser.parseDimensionsWithErrors(
                "{overworld=the_end}{the_end=overworld}");
        assertTrue(cycle.errors().stream().anyMatch(e -> e.contains("alias cycle")),
                cycle.errors().toString());
        assertTrue(cycle.dimensions().isEmpty());

        FormulaParser.DimensionParseResult self = FormulaParser.parseDimensionsWithErrors(
                "{overworld=y=0: rand()}{the_end=the_end}");
        assertTrue(self.errors().stream().anyMatch(e -> e.contains("alias cycle")),
                self.errors().toString());
    }

    /** 节内的 let 花括号不影响节边界（深度计数），且与分节语法共存。 */
    @Test
    void letBracesStayInsideASection() {
        FormulaParser.DimensionParseResult result = FormulaParser.parseDimensionsWithErrors(
                "{overworld=y=0: { let a = 1; a > 0 ? rand() : rand() }}");
        assertTrue(result.errors().isEmpty(), result.errors().toString());
        assertEquals(1, result.dimensions().size());
        assertEquals(1, result.dimensions().get(FormulaParser.DIM_OVERWORLD).layers().size());
    }

    // ── 维度指令（P4：[structure:...] / [biome:...]） ─────────────────────────

    /** 结构指令：none / only / except / all 解析，名称规范化（去 minecraft: 前缀）。 */
    @Test
    void structureDirectivesParse() {
        FormulaParser.DimensionParseResult none = FormulaParser.parseDimensionsWithErrors(
                "{the_nether=[structure:none] y=0: rand()}");
        assertTrue(none.errors().isEmpty(), none.errors().toString());
        assertSame(DimensionRules.StructureRule.Mode.NONE,
                none.dimensions().get(FormulaParser.DIM_NETHER).structure().mode());

        FormulaParser.DimensionParseResult only = FormulaParser.parseDimensionsWithErrors(
                "{overworld=[structure:only=minecraft:villages,strongholds] y=0: rand()}");
        assertTrue(only.errors().isEmpty(), only.errors().toString());
        DimensionRules.StructureRule rule = only.dimensions().get(FormulaParser.DIM_OVERWORLD).structure();
        assertEquals(DimensionRules.StructureRule.Mode.ONLY, rule.mode());
        assertEquals(List.of("villages", "strongholds"), rule.names());

        FormulaParser.DimensionParseResult except = FormulaParser.parseDimensionsWithErrors(
                "{overworld=[structure:except=mineshafts] y=0: rand()}");
        assertTrue(except.errors().isEmpty(), except.errors().toString());
        assertSame(DimensionRules.StructureRule.Mode.EXCEPT,
                except.dimensions().get(FormulaParser.DIM_OVERWORLD).structure().mode());

        FormulaParser.DimensionParseResult all = FormulaParser.parseDimensionsWithErrors(
                "{overworld=[structure:all] y=0: rand()}");
        assertTrue(all.errors().isEmpty(), all.errors().toString());
        assertTrue(all.dimensions().get(FormulaParser.DIM_OVERWORLD).structure().isDefault());
    }

    /** 结构规则匹配：组名与成员名都能命中；ONLY / EXCEPT / ALL / NONE 语义。 */
    @Test
    void structureRuleMatching() {
        DimensionRules.StructureRule onlySet = new DimensionRules.StructureRule(
                DimensionRules.StructureRule.Mode.ONLY, List.of("villages"));
        assertTrue(onlySet.allows("villages", List.of("village_plains")));
        assertFalse(onlySet.allows("mineshafts", List.of("mineshaft")));

        DimensionRules.StructureRule onlyMember = new DimensionRules.StructureRule(
                DimensionRules.StructureRule.Mode.ONLY, List.of("village_plains"));
        assertTrue(onlyMember.allows("villages", List.of("village_plains")));
        assertFalse(onlyMember.allows("mineshafts", List.of("mineshaft")));

        DimensionRules.StructureRule except = new DimensionRules.StructureRule(
                DimensionRules.StructureRule.Mode.EXCEPT, List.of("strongholds"));
        assertFalse(except.allows("strongholds", List.of("stronghold")));
        assertTrue(except.allows("villages", List.of("village_plains")));

        assertTrue(DimensionRules.StructureRule.ALL.allows("strongholds", List.of("stronghold")));
        assertFalse(new DimensionRules.StructureRule(
                DimensionRules.StructureRule.Mode.NONE, List.of()).allows("villages", List.of()));
    }

    /** 结构指令错误：未知名（无命名空间）/ 自定义命名空间放行 / 重复指令 / 空名单。 */
    @Test
    void structureDirectiveErrors() {
        FormulaParser.DimensionParseResult unknown = FormulaParser.parseDimensionsWithErrors(
                "{overworld=[structure:only=village] y=0: rand()}");
        assertTrue(unknown.errors().stream().anyMatch(e -> e.contains("unknown structure")),
                unknown.errors().toString());

        FormulaParser.DimensionParseResult custom = FormulaParser.parseDimensionsWithErrors(
                "{overworld=[structure:only=mymod:tower] y=0: rand()}");
        assertTrue(custom.errors().isEmpty(), custom.errors().toString());

        FormulaParser.DimensionParseResult duplicate = FormulaParser.parseDimensionsWithErrors(
                "{overworld=[structure:all][structure:none] y=0: rand()}");
        assertTrue(duplicate.errors().stream().anyMatch(e -> e.contains("duplicate structure directive")),
                duplicate.errors().toString());

        FormulaParser.DimensionParseResult empty = FormulaParser.parseDimensionsWithErrors(
                "{overworld=[structure:only=] y=0: rand()}");
        assertTrue(empty.errors().stream().anyMatch(e -> e.contains("empty name in structure list")),
                empty.errors().toString());
    }

    /** 群系指令：vanilla / 单群系 / 未知群系 / 未知指令。 */
    @Test
    void biomeDirectivesParse() {
        FormulaParser.DimensionParseResult vanilla = FormulaParser.parseDimensionsWithErrors(
                "{overworld=[biome:vanilla] y=0: rand()}");
        assertTrue(vanilla.errors().isEmpty(), vanilla.errors().toString());
        assertTrue(vanilla.dimensions().get(FormulaParser.DIM_OVERWORLD).biome().vanilla());

        FormulaParser.DimensionParseResult single = FormulaParser.parseDimensionsWithErrors(
                "{overworld=[biome:minecraft:desert] y=0: rand()}");
        assertTrue(single.errors().isEmpty(), single.errors().toString());
        assertEquals("desert", single.dimensions().get(FormulaParser.DIM_OVERWORLD).biome().singleBiomeId());

        FormulaParser.DimensionParseResult unknown = FormulaParser.parseDimensionsWithErrors(
                "{overworld=[biome:not_a_biome] y=0: rand()}");
        assertTrue(unknown.errors().stream().anyMatch(e -> e.contains("unknown biome")),
                unknown.errors().toString());

        FormulaParser.DimensionParseResult badDirective = FormulaParser.parseDimensionsWithErrors(
                "{overworld=[foo:bar] y=0: rand()}");
        assertTrue(badDirective.errors().stream().anyMatch(e -> e.contains("unknown directive")),
                badDirective.errors().toString());
    }

    /** 特性开关指令：none / all / 无指令默认 / 非法值 / 重复。 */
    @Test
    void featuresDirectivesParse() {
        FormulaParser.DimensionParseResult none = FormulaParser.parseDimensionsWithErrors(
                "{the_nether=[features:none] y=0: rand()}");
        assertTrue(none.errors().isEmpty(), none.errors().toString());
        assertTrue(none.dimensions().get(FormulaParser.DIM_NETHER).featuresOff());

        FormulaParser.DimensionParseResult all = FormulaParser.parseDimensionsWithErrors(
                "{the_nether=[features:all] y=0: rand()}");
        assertTrue(all.errors().isEmpty(), all.errors().toString());
        assertFalse(all.dimensions().get(FormulaParser.DIM_NETHER).featuresOff());

        FormulaParser.DimensionParseResult absent = FormulaParser.parseDimensionsWithErrors(
                "{the_nether=y=0: rand()}");
        assertTrue(absent.errors().isEmpty(), absent.errors().toString());
        assertFalse(absent.dimensions().get(FormulaParser.DIM_NETHER).featuresOff());

        FormulaParser.DimensionParseResult bad = FormulaParser.parseDimensionsWithErrors(
                "{the_nether=[features:maybe] y=0: rand()}");
        assertTrue(bad.errors().stream().anyMatch(e -> e.contains("invalid features mode")),
                bad.errors().toString());

        FormulaParser.DimensionParseResult duplicate = FormulaParser.parseDimensionsWithErrors(
                "{the_nether=[features:none][features:all] y=0: rand()}");
        assertTrue(duplicate.errors().stream().anyMatch(e -> e.contains("duplicate features directive")),
                duplicate.errors().toString());
    }

    /** 指令与别名共存：别名共享目标的指令与层表。 */
    @Test
    void directivesCombineWithAliases() {
        FormulaParser.DimensionParseResult result = FormulaParser.parseDimensionsWithErrors(
                "{overworld=[structure:only=villages] [biome:vanilla] y=0: { let a = 1; a > 0 ? rand() : rand() }}"
                        + "{the_end=overworld}");
        assertTrue(result.errors().isEmpty(), result.errors().toString());
        assertEquals(2, result.dimensions().size());
        assertSame(result.dimensions().get(FormulaParser.DIM_OVERWORLD),
                result.dimensions().get(FormulaParser.DIM_END));
        assertSame(DimensionRules.StructureRule.Mode.ONLY,
                result.dimensions().get(FormulaParser.DIM_END).structure().mode());
        assertTrue(result.dimensions().get(FormulaParser.DIM_END).biome().vanilla());
    }

    /** 共享节级 let：供所有层复用，支持链式引用与循环层条目。 */
    @Test
    void sharedSectionLetsAreUsableFromLayers() {
        assertOnlyReturnsBlockTypeError("let a = 2 + 3; y=0: a * seed");
        assertOnlyReturnsBlockTypeError("let a = 2; let b = a * 3; y=0: b + seed");

        FormulaParser.ParseResult cyclic = FormulaParser.parseWithErrors(
                "let a = 2; y=0..4: 1*[a * seed], 1*[a + seed]");
        assertFalse(cyclic.errors().isEmpty());
        assertTrue(cyclic.errors().stream().allMatch(e -> e.contains("must return a block")),
                cyclic.errors().toString());
    }

    /** 层内 let 可以遮蔽同名共享绑定（作用域规则与嵌套 let 一致）。 */
    @Test
    void layerLocalLetShadowsSharedLet() {
        assertOnlyReturnsBlockTypeError("let a = 2; y=0: { let a = 5; a * seed }");
    }

    /** 共享 let 自身写错时报专门错误；绑定内部的未知变量照常报告。 */
    @Test
    void brokenSharedLetsReportClearly() {
        FormulaParser.ParseResult noName = FormulaParser.parseWithErrors("let = 5; y=0: 1");
        assertEquals(1, noName.errors().size(), noName.errors().toString());
        assertTrue(noName.errors().get(0).contains("variable name"), noName.errors().toString());

        FormulaParser.ParseResult unknown = FormulaParser.parseWithErrors("let a = zzz; y=0: a * seed");
        assertTrue(unknown.errors().stream().anyMatch(e -> e.contains("Unknown variable: zzz")),
                unknown.errors().toString());
    }

    /** 群系行：简写 / 分层 / 共享 let / fallback 指令。 */
    @Test
    void biomeLinesParse() {
        FormulaParser.DimensionParseResult shorthand = FormulaParser.parseDimensionsWithErrors(
                "{overworld=biome: minecraft:desert; y=0: rand()}");
        assertTrue(shorthand.errors().isEmpty(), shorthand.errors().toString());
        FormulaParser.ParsedDimension overworld = shorthand.dimensions().get(FormulaParser.DIM_OVERWORLD);
        assertEquals(1, overworld.biomeLayers().size());
        assertTrue(overworld.biomeLayers().get(0).shorthand());
        assertSame(DimensionRules.BiomeFallback.NONE, overworld.biomeFallback());

        FormulaParser.DimensionParseResult layered = FormulaParser.parseDimensionsWithErrors(
                "{overworld=[biome-fallback:3d]"
                        + " let warm = seedhash(x, z, 3);"
                        + " biome: warm < 0.5 ? minecraft:desert : minecraft:plains;"
                        + " biome y=0..320: warm < 0.3 ? minecraft:swamp : minecraft:forest;"
                        + " y=0: rand()}");
        assertTrue(layered.errors().isEmpty(), layered.errors().toString());
        overworld = layered.dimensions().get(FormulaParser.DIM_OVERWORLD);
        assertEquals(2, overworld.biomeLayers().size());
        assertTrue(overworld.biomeLayers().get(0).shorthand());
        assertFalse(overworld.biomeLayers().get(1).shorthand());
        assertEquals(0, overworld.biomeLayers().get(1).yStart());
        assertEquals(320, overworld.biomeLayers().get(1).yEnd());
        assertSame(DimensionRules.BiomeFallback.THREE_D, overworld.biomeFallback());
    }

    /** 群系行/指令的错误路径：未知群系、rand、返回数字、互斥、缺行、非法模式。 */
    @Test
    void biomeLinesReportErrors() {
        FormulaParser.DimensionParseResult unknown = FormulaParser.parseDimensionsWithErrors(
                "{overworld=biome: minecraft:not_a_biome; y=0: rand()}");
        assertTrue(unknown.errors().stream().anyMatch(e -> e.contains("Unknown biome: minecraft:not_a_biome")),
                unknown.errors().toString());

        FormulaParser.DimensionParseResult rand = FormulaParser.parseDimensionsWithErrors(
                "{overworld=biome: rand(); y=0: rand()}");
        assertTrue(rand.errors().stream().anyMatch(e -> e.contains("cannot be used in a biome expression")),
                rand.errors().toString());

        FormulaParser.DimensionParseResult number = FormulaParser.parseDimensionsWithErrors(
                "{overworld=biome: 1 + 2; y=0: rand()}");
        assertTrue(number.errors().stream().anyMatch(e -> e.contains("must return a biome")),
                number.errors().toString());

        FormulaParser.DimensionParseResult conflict = FormulaParser.parseDimensionsWithErrors(
                "{overworld=[biome:vanilla] biome: minecraft:desert; y=0: rand()}");
        assertTrue(conflict.errors().stream().anyMatch(e -> e.contains("cannot be combined with biome lines")),
                conflict.errors().toString());

        FormulaParser.DimensionParseResult dangling = FormulaParser.parseDimensionsWithErrors(
                "{overworld=[biome-fallback:2d] y=0: rand()}");
        assertTrue(dangling.errors().stream().anyMatch(e -> e.contains("requires at least one biome line")),
                dangling.errors().toString());

        FormulaParser.DimensionParseResult mode = FormulaParser.parseDimensionsWithErrors(
                "{overworld=[biome-fallback:2D] y=0: rand()}");
        assertTrue(mode.errors().stream().anyMatch(e -> e.contains("invalid biome-fallback mode")),
                mode.errors().toString());
    }

    /** biome 行的地形查询：只允许出现在 biome 行；参数类型与个数按参数位校验。 */
    @Test
    void biomeTerrainQueriesValidate() {
        // biome 行可用（方块实参用 rand() 替身，测试 JVM 不碰注册表）
        FormulaParser.DimensionParseResult ok = FormulaParser.parseDimensionsWithErrors(
                "{overworld=[biome-fallback:3d] y=0: rand();"
                        + "biome: terrain(x, z) > 0 ? (surfis(x, z, rand()) ? minecraft:desert : minecraft:plains)"
                        + " : (blockis(x, 0, terrain(x, z) - 1, rand()) ? minecraft:badlands : minecraft:savanna)}");
        assertTrue(ok.errors().isEmpty(), ok.errors().toString());

        // 方块层不能用（方块分支同样用 rand() 替身）
        FormulaParser.DimensionParseResult blockLayer = FormulaParser.parseDimensionsWithErrors(
                "{overworld=y=0: terrain(x, z) > 0 ? rand() : rand()}");
        assertTrue(blockLayer.errors().stream().anyMatch(e -> e.contains("can only be used in biome lines")),
                blockLayer.errors().toString());

        // 坐标参数必须是数字
        FormulaParser.DimensionParseResult badArg = FormulaParser.parseDimensionsWithErrors(
                "{overworld=biome: surfis(minecraft:desert, z, rand()) ? minecraft:desert : minecraft:plains; y=0: rand()}");
        assertTrue(badArg.errors().stream().anyMatch(e -> e.contains("Expected a number")),
                badArg.errors().toString());

        // 参数个数
        FormulaParser.DimensionParseResult arity = FormulaParser.parseDimensionsWithErrors(
                "{overworld=biome: terrain(x) > 0 ? minecraft:desert : minecraft:plains; y=0: rand()}");
        assertTrue(arity.errors().stream().anyMatch(e -> e.contains("expects 2 argument")),
                arity.errors().toString());
    }

    /** 方块层的群系查询：参数位、仅限方块层、B/C 互斥与 2d 组合守卫。 */
    @Test
    void blockBiomeQueriesValidate() {
        // 方块层可用；[biome:vanilla] 是允许的组合
        FormulaParser.DimensionParseResult ok = FormulaParser.parseDimensionsWithErrors(
                "{overworld=[biome:vanilla] y=-64: rand();"
                        + "y=-63..64: biomeis(x, z, 64, minecraft:desert) ? rand() : rand()}");
        assertTrue(ok.errors().isEmpty(), ok.errors().toString());

        // 只能出现在方块层
        FormulaParser.DimensionParseResult inBiomeRow = FormulaParser.parseDimensionsWithErrors(
                "{overworld=[biome-fallback:3d] biome: biomeis(x, z, 64, minecraft:desert) "
                        + "? minecraft:desert : minecraft:plains; y=0: rand()}");
        assertTrue(inBiomeRow.errors().stream().anyMatch(e -> e.contains("can only be used in block layers")),
                inBiomeRow.errors().toString());

        // 末位必须是单个群系字面量
        FormulaParser.DimensionParseResult notLiteral = FormulaParser.parseDimensionsWithErrors(
                "{overworld=[biome:vanilla] y=0: biomeis(x, z, 64, rand()) ? rand() : rand()}");
        assertTrue(notLiteral.errors().stream().anyMatch(e -> e.contains("expects a single biome literal")),
                notLiteral.errors().toString());

        // 未知群系
        FormulaParser.DimensionParseResult unknown = FormulaParser.parseDimensionsWithErrors(
                "{overworld=[biome:vanilla] y=0: biomeis(x, z, 64, minecraft:not_a_biome) ? rand() : rand()}");
        assertTrue(unknown.errors().stream().anyMatch(e -> e.contains("Unknown biome: minecraft:not_a_biome")),
                unknown.errors().toString());

        // B/C 互斥：biome 行读地形 + 方块层读群系
        FormulaParser.DimensionParseResult cycle = FormulaParser.parseDimensionsWithErrors(
                "{overworld=[biome-fallback:3d] y=0: biomeis(x, z, 64, minecraft:desert) ? rand() : rand();"
                        + "biome: surfis(x, z, rand()) ? minecraft:desert : minecraft:plains}");
        assertTrue(cycle.errors().stream().anyMatch(e -> e.contains("would form a cycle")),
                cycle.errors().toString());

        // 2d 回退不能与 biomeis 组合
        FormulaParser.DimensionParseResult twoD = FormulaParser.parseDimensionsWithErrors(
                "{overworld=[biome-fallback:2d] y=0: biomeis(x, z, 64, minecraft:desert) ? rand() : rand();"
                        + "biome y=-64..319: minecraft:desert}");
        assertTrue(twoD.errors().stream().anyMatch(e -> e.contains("2d cannot be combined with biomeis")),
                twoD.errors().toString());
    }

    /** 参数化共享 let（宏）：调用内联、遮蔽、参数个数与内置函数冲突校验。 */
    @Test
    void parametricSharedLetsExpand() {
        assertOnlyReturnsBlockTypeError("let h(px, pz) = px * 2 + pz; y=0: h(x, z) * seed;");
        assertOnlyReturnsBlockTypeError("let k() = seed; y=0: k() * 3;");

        // 邻近采样（坡度）与循环层、群系行里同样可用
        FormulaParser.DimensionParseResult all = FormulaParser.parseDimensionsWithErrors(
                "{overworld=[biome-fallback:3d] let n(px, pz) = seedhash(px, pz, 1);"
                        + "y=0..4: 1*[n(x, z) < 0.5 ? rand() : rand()];"
                        + "biome: n(x, z) < 0.5 ? minecraft:desert : minecraft:plains}");
        assertTrue(all.errors().isEmpty(), all.errors().toString());

        // 宏体内的同名 let 绑定遮蔽参数（没有遮蔽处理的话 rand() 会被代入、报数值类型错误）
        assertOnlyReturnsBlockTypeError("let f(a) = { let a = 5; a }; y=0: f(rand()) * seed;");

        FormulaParser.ParseResult arity = FormulaParser.parseWithErrors("let f(a) = a; y=0: f(1, 2)");
        assertTrue(arity.errors().stream().anyMatch(e -> e.contains("expects 1 argument")),
                arity.errors().toString());

        FormulaParser.ParseResult collision = FormulaParser.parseWithErrors("let rand(a) = a; y=0: 1");
        assertTrue(collision.errors().stream().anyMatch(e -> e.contains("collides with a built-in function")),
                collision.errors().toString());

        FormulaParser.ParseResult selfRef = FormulaParser.parseWithErrors("let f(a) = f(a); y=0: f(1)");
        assertTrue(selfRef.errors().stream().anyMatch(e -> e.contains("too deep")),
                selfRef.errors().toString());
    }

    /** [carvers:vanilla] 指令：解析、取值与非法模式。 */
    @Test
    void carversDirectiveParses() {
        FormulaParser.DimensionParseResult vanilla = FormulaParser.parseDimensionsWithErrors(
                "{overworld=[carvers:vanilla] y=0: rand()}");
        assertTrue(vanilla.errors().isEmpty(), vanilla.errors().toString());
        assertEquals(DimensionRules.CarversMode.VANILLA,
                vanilla.dimensions().get(FormulaParser.DIM_OVERWORLD).carvers());

        FormulaParser.DimensionParseResult none = FormulaParser.parseDimensionsWithErrors(
                "{overworld=[carvers:none] y=0: rand()}");
        assertTrue(none.errors().isEmpty(), none.errors().toString());
        assertEquals(DimensionRules.CarversMode.NONE,
                none.dimensions().get(FormulaParser.DIM_OVERWORLD).carvers());

        FormulaParser.DimensionParseResult bad = FormulaParser.parseDimensionsWithErrors(
                "{overworld=[carvers:maybe] y=0: rand()}");
        assertTrue(bad.errors().stream().anyMatch(e -> e.contains("invalid carvers mode")),
                bad.errors().toString());
    }

    /** 指南/抽查清单里给出的组合示例（方块层用 rand() 替代，避免触碰注册表）。 */
    @Test
    void documentedExamplesParse() {
        String[] valid = {
                // 1. 两段全覆盖 + none（纵向分层）
                "{overworld=y=-64: rand();y=-63..64: rand();"
                        + "biome y=-64..30: minecraft:deep_dark;biome y=31..319: minecraft:desert}",
                // 2. 3d 回退 + 三维度独立
                "{overworld=[biome-fallback:3d] y=-64: rand();y=-63..64: rand();"
                        + "biome y=-64..30: minecraft:deep_dark}"
                        + "{the_nether=[biome-fallback:3d] y=0..40: rand();biome y=0..20: minecraft:basalt_deltas}"
                        + "{the_end=[biome-fallback:3d] y=0..60: rand();biome y=0..30: minecraft:the_end}",
                // 3. 2d 回退（表面采样、整列覆盖）
                "{overworld=[biome-fallback:2d] y=-64: rand();y=-63..64: rand();biome y=-64..0: minecraft:lush_caves}",
                // 4. 共享 let 参与群系三元
                "{overworld=let warm = seedhash(floordiv(x, 64), floordiv(z, 64), 5);"
                        + "y=-64: rand();y=-63..64: rand();"
                        + "biome y=-64..30: warm < 0.5 ? minecraft:lush_caves : minecraft:dripstone_caves;"
                        + "biome y=31..319: warm < 0.5 ? minecraft:desert : minecraft:plains}",
                // 5. 深板岩过渡：逐方块哈希噪声 vs 高度斜坡（y 用 ly 换算）
                "{overworld=y=-64..8: { let yy = ly - 64;"
                        + " seedhash(x, yy, z, 11) < min(max((8 - yy) / 8, 0), 1) ? rand() : rand() };"
                        + "y=9..63: rand()}",
                // 6. 自然世界配方：起伏地形（宏 + 噪声 + y）
                "{overworld=let cont = noise2(x, z, 1400, 1);"
                        + " let hill = fbm2(x, z, 320, 4, 2);"
                        + " let peak = smoothstep(noise2(x, z, 900, 3) * 0.7 + 0.5);"
                        + " let h = 64 + cont * 36 + hill * 10 + peak * 30;"
                        + " y=-64..319: y <= h ? (y > h - 4 ? rand() : rand()) : rand()}",
                // 7. 自然世界配方：交错洞穴（写进地形层最前面）
                "{overworld=let h = 64 + noise2(x, z, 900, 1) * 24;"
                        + " let cave = noise3(x, y * 2.2, z, 96, 7);"
                        + " let tunnel = abs(noise3(x, y, z, 34, 8));"
                        + " let open = lerp(0.22, 0.45, clamp((h - y) / 28, 0, 1));"
                        + " y=-64..319: (cave > open || tunnel < 0.006) && y < h - 3 ? rand()"
                        + " : (y <= h ? (y > h - 4 ? rand() : rand()) : rand())}",
                // 8. 自然世界配方：下界式混沌洞窟
                "{the_nether=let blob = fbm3(x, y * 1.6, z, 150, 3, 21);"
                        + " y=0..127: blob > 0.20 ? rand() : rand()}",
                // 9. 自然世界配方：末地式零碎岛屿
                "{the_end=let r = sqrt(x * x + z * z);"
                        + " let islands = worley2(x, z, 240, 4);"
                        + " let edge = fbm2(x, z, 120, 3, 5) * 6;"
                        + " let top = 90 - r * 0.10 + edge;"
                        + " y=0..255: y <= top * smoothstep(0.55 - islands * 1.6) ? rand() : rand()}",
        };
        for (String formula : valid) {
            FormulaParser.DimensionParseResult result = FormulaParser.parseDimensionsWithErrors(formula);
            assertTrue(result.errors().isEmpty(), formula + " => " + result.errors());
        }
    }

    /** fallback=none（缺省）要求群系行覆盖整维；简写与多段拼接都可以。 */
    @Test
    void biomeFallbackNoneRequiresFullCoverage() {
        FormulaParser.DimensionParseResult gap = FormulaParser.parseDimensionsWithErrors(
                "{overworld=biome y=0..319: minecraft:plains; y=0: rand()}");
        assertTrue(gap.errors().stream().anyMatch(e -> e.contains("must cover the whole dimension")),
                gap.errors().toString());

        FormulaParser.DimensionParseResult stitched = FormulaParser.parseDimensionsWithErrors(
                "{overworld=biome y=-64..0: minecraft:deep_dark; biome y=1..319: minecraft:plains; y=0: rand()}");
        assertTrue(stitched.errors().isEmpty(), stitched.errors().toString());

        FormulaParser.DimensionParseResult topLevel = FormulaParser.parseDimensionsWithErrors(
                "biome y=0..319: minecraft:plains; y=0: rand()");
        assertTrue(topLevel.errors().stream().anyMatch(e -> e.contains("must cover the whole dimension")),
                topLevel.errors().toString());
    }
}
