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
        assertTrue(variable.errors().get(0).contains("available: x, z, ly, seed"),
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
}
