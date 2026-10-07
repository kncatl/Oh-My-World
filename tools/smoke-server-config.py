#!/usr/bin/env python3
"""为冒烟测试写服务端配置（开发服与真 jar 验证共用）。

用法: smoke-server-config.py <服务端目录> <端口> [--force-formula] [--seed-formula]
                            [--dimension-formula | --dimension-alias]

做的事：
  * eula.txt = true
  * server.properties：level-type=flat（**必须**，否则本模组 Mixin 不触发、
    冒烟会变成"服务器起来了但公式没跑"的假阳性）、固定种子、关结构、独立端口。
  * config/ohmyworld.json：若缺失（或传了 --force-formula）则写入冒烟公式：
    公式刻意使用**白名单之外**的方块（触发 RegistryLookup 的版本差异路径），
    并以 sea_lantern 作为特征方块供 smoke-check-world.py 核验。

--seed-formula：改用"种子专项"冒烟公式（配合固定的 level-seed=12345）：
  仅当 `seed == 12345` 时铺方块，并用 `seedhash(x, z, 0) < 0.5` 在两种特征方块间
  切换。seed 读错 → 世界无特征方块；seedhash 退化成常量 → 只剩一种特征方块。
  因此该模式要用 smoke-check-world.py --require-all 判定。

--spawn-formula：改用"出生点专项"冒烟公式：以 (spawnx, spawnz) 为圆心、半径 12
  的圆盘铺海晶灯，其余铺黑石砖。出生点读错（例如恒为 0）时灯盘不会落在
  level.dat 的 SpawnX/SpawnZ 上——配合 smoke-check-world.py --require-all，
  再用 dump-biomes.py spawn 与块扫描复核灯盘圆心。

--dimension-formula：分节冒烟（1）：{overworld=...}{the_nether=...}——末地未写
  按原版；配合控制台 forceload 生成下界/末地区块后用
  smoke-check-world.py --dimension-smoke 1 判定。
--dimension-alias：分节冒烟（2）：{the_nether=...}{the_end=the_nether}——没有
  overworld 节（主世界=超平坦基座、无公式）、末地走别名。配合
  smoke-check-world.py --dimension-smoke 2 判定。
--marker-formula：marker 驱动冒烟：server_mode=false，预先在 world 目录写入
  与模式（1）相同的分节 marker——验证「已有世界 + 分节 marker」的恢复路径
  （server_mode 冒烟不经过它）。核验规则同 --dimension-smoke 1。

--biome-desert / --biome-vanilla（P4.2）：主世界 [biome:...] 群系冒烟；配合
  smoke-check-world.py --biome-smoke 1|2 判定（区块 NBT 群系调色板扫描）。
--biome-formula / --biome-formula-fallback（公式群系）：主世界 biome 行冒烟；
  配合 smoke-check-world.py --biome-smoke 3|4 判定。
  3 = 两段全覆盖（y=-64..30 深暗之域 / y=31..319 沙漠，fallback 缺省 none）；
  4 = [biome-fallback:3d]：低段深暗之域 + 其余按实际 y 交还原版分布。
--biome-terrain：地形联动冒烟（biome 行调用 terrain/surfis/blockis）；
  配合 smoke-check-world.py --biome-smoke 5 判定（三类群系都出现才算通过）。
--biome-biomeis：[biome:vanilla] + 方块层 biomeis 冒烟；配合
  smoke-check-world.py --biome-smoke 6 判定（两种"按群系铺的方块"都要出现）。
--natural：自然世界冒烟（1.2.5：noise/fbm + y 变量 + 洞穴）；配合
  smoke-check-world.py --biome-smoke 7 判定（地表材质方块都要出现）。
--carvers / --carvers-off：雕刻器 A/B 冒烟（**普通世界**类型；超平坦世界的雕刻器
  由 1.2.6 起通过"干燥代理"支持，见下一条）；公式为整块石头 + [carvers:vanilla|none]；
  配合 --biome-smoke 8，再用 dump-biomes.py stats 对比 y 段空气率判定是否真的被掏空。
--flat-carvers / --flat-carvers-off：超平坦雕刻器 A/B 冒烟（**我们自己的 flat_plus 预设**）；
  公式同上（整块石头到 y=80 + [carvers:vanilla|none]），用于验证 1.2.6 的
  "超平坦世界跑原版雕刻器"（干燥代理）确实在公式地形上掏出了洞穴/峡谷：
  carvers-on 的空气率应显著高于 carvers-off（后者≈0）。配合 --biome-smoke 8。
--features-all / --features-none（P4.3）：下界固定玄武岩三角洲 + [features:...]
  装饰开关冒烟；配合 smoke-check-world.py --features-smoke 1|2 判定（装饰方块扫描）。

输出最后一行：SMOKE_FORMULA=1 表示当前配置就是我们写入的冒烟公式（应做特征方块核验）；
SMOKE_FORMULA=0 表示保留了已有公式（不要按特征方块判定）。
"""

import sys
from pathlib import Path

PROPERTIES = {
    "level-name": "world",
    "level-type": "minecraft\\:flat",
    "level-seed": "12345",
    "generate-structures": "false",
    "online-mode": "false",
    "spawn-protection": "0",
    "view-distance": "4",
    "simulation-distance": "4",
    "max-players": "1",
}

# 两个方块都不在 BlockResolver 的硬编码白名单里 → 会走注册表查询。
FORMULA = (
    "y=-64: minecraft:bedrock;"
    "y=-63..64: (x+z)%2==0 ? minecraft:sea_lantern : minecraft:polished_blackstone_bricks"
)

# 种子专项冒烟：level-seed 固定为 12345（见 PROPERTIES）。
# 语义：seed 读错 → 全空气 → 没有特征方块；seedhash 退化成常量 → 只剩一种。
SEED_FORMULA = (
    "y=-64: minecraft:bedrock;"
    "y=-63..64: (seed == 12345)"
    " ? (seedhash(x, z, 0) < 0.5 ? minecraft:sea_lantern : minecraft:polished_blackstone_bricks)"
    " : minecraft:air"
)

# 出生点专项冒烟：以出生点（出生区块中心）为圆心、半径 12 的圆盘铺海晶灯，
# 其余铺黑石砖。出生点读错（如恒为 0）→ 灯盘不在预期中心；
# 配合 --require-all（两块都要出现）+ dump-biomes.py spawn 复核圆心
# （预期中心 = 出生区块中心 = 存档出生点所在区块的中点）。
SPAWN_FORMULA = (
    "y=-64: minecraft:bedrock;"
    "y=-63..64: ((x - spawnx) * (x - spawnx) + (z - spawnz) * (z - spawnz) <= 144)"
    " ? minecraft:sea_lantern : minecraft:polished_blackstone_bricks"
)

# 分节冒烟（1）：主世界 + 下界各写公式，末地不写（必须保持原版）。
# 下界用 ochre_froglight（非自然生成、非白名单，同样走注册表查询）。
DIMENSION_FORMULA = (
    "{overworld="
    "y=-64: minecraft:bedrock;"
    "y=-63..64: (x+z)%2==0 ? minecraft:sea_lantern : minecraft:polished_blackstone_bricks"
    "}"
    "{the_nether="
    "y=0..40: minecraft:ochre_froglight"
    "}"
)

# 分节冒烟（2）：没有 overworld 节 + 末地走别名（= 下界公式）。
DIMENSION_ALIAS_FORMULA = (
    "{the_nether="
    "y=0..40: minecraft:ochre_froglight"
    "}"
    "{the_end=the_nether}"
)

# P4 结构冒烟：主世界公式带 [structure:...] 指令（配合 16×16 区块 forceload 网格与
# smoke-check-world.py --structure-smoke 的结构 ID 扫描）。
# 用废弃矿井当白名单样本：密度高（每区块候选）、平原群系有效、区块 NBT 里能扫到
# 结构 ID（"minecraft:mineshaft"）。
STRUCTURE_NONE_FORMULA = (
    "{overworld=[structure:none] "
    "y=-64: minecraft:bedrock;y=-63..0: minecraft:stone;y=1..64: minecraft:air}"
)

STRUCTURE_ONLY_FORMULA = (
    "{overworld=[structure:only=mineshafts] "
    "y=-64: minecraft:bedrock;y=-63..0: minecraft:stone;y=1..64: minecraft:air}"
)

# P4.2 群系冒烟：主世界固定沙漠 / 原版群系分布（配合 smoke-check-world.py --biome-smoke）。
# 群系 id 存在区块 NBT 的群系调色板里，直接扫描即可；地形层用来确认区块确已生成。
BIOME_DESERT_FORMULA = (
    "{overworld=[biome:minecraft:desert] "
    "y=-64: minecraft:bedrock;y=-63..0: minecraft:stone;y=1..64: minecraft:air}"
)

BIOME_VANILLA_FORMULA = (
    "{overworld=[biome:vanilla] "
    "y=-64: minecraft:bedrock;y=-63..0: minecraft:stone;y=1..64: minecraft:air}"
)

# 群系专属结构解锁冒烟：固定沙漠 + 只放沙漠神殿。原版在 ChunkMap 构造期按"当时的
# 群系源（平原）"过滤过结构组，沙漠神殿本不在候选里——本模组换群系后会重建结构组
# 状态；若重建失败，这里将扫不到 desert_pyramid。配合 --structure-smoke 3。
# 公式群系冒烟（配合 smoke-check-world.py --biome-smoke 3|4）：
# 3 = 两段全覆盖（低段深暗之域 / 高段沙漠，fallback 缺省 none——两行都必须生效）；
# 4 = [biome-fallback:3d]：低段深暗之域 + 其余按实际 y 交还原版分布。
BIOME_FORMULA_FORMULA = (
    "{overworld="
    "y=-64: minecraft:bedrock;"
    "y=-63..64: (x+z)%2==0 ? minecraft:sea_lantern : minecraft:polished_blackstone_bricks;"
    "biome y=-64..30: minecraft:deep_dark;"
    "biome y=31..319: minecraft:desert}"
)
BIOME_FORMULA_FALLBACK_FORMULA = (
    "{overworld=[biome-fallback:3d] "
    "y=-64: minecraft:bedrock;"
    "y=-63..64: (x+z)%2==0 ? minecraft:sea_lantern : minecraft:polished_blackstone_bricks;"
    "biome y=-64..30: minecraft:deep_dark}"
)

# 地形联动冒烟（配合 smoke-check-world.py --biome-smoke 5）：
# 群系由"公式地形"决定——blockis 命中网格点 → 冰刺之地；否则按 surfis 的
# 4 格棋盘 → 沙漠/恶地；并且要求 terrain(x,z) 确实等于表面高度 64。
# 三个函数任一失效都会导致某类群系缺失（读的是公式而不是已生成方块，
# 若实现改读区块方块，群系填充阶段还是空气 → 也会缺失）。
BIOME_TERRAIN_FORMULA = (
    "{overworld="
    "y=-64: minecraft:bedrock;"
    "y=-63..64: (floordiv(x, 4) + floordiv(z, 4)) % 2 == 0"
    " ? minecraft:sea_lantern : minecraft:polished_blackstone_bricks;"
    "y=-20: (floormod(x, 8) == 0 && floormod(z, 8) == 0)"
    " ? minecraft:gold_block : minecraft:iron_block;"
    "biome: blockis(x, z, -20, minecraft:gold_block) ? minecraft:ice_spikes"
    " : ((terrain(x, z) == 64) && surfis(x, z, minecraft:sea_lantern)"
    " ? minecraft:desert : minecraft:badlands)}"
)

# 群系回读冒烟（配合 smoke-check-world.py --biome-smoke 6）：
# [biome:vanilla] + 方块层 biomeis。
#   · 出生区 y=64 稳定有海洋（各大版本都是）：应铺海晶灯；
#   · 沙漠在出生区不该命中：金块必须一个都不出现——这条用来抓住
#     "biomeis 恒真" 的实现错误；
#   · 其余铺磨制黑石砖（只作背景，不作为判定项）。
BIOME_BIOMEIS_FORMULA = (
    "{overworld=[biome:vanilla] "
    "y=-64: minecraft:bedrock;"
    "y=-63..64: biomeis(x, z, 64, minecraft:desert) ? minecraft:gold_block"
    " : (biomeis(x, z, 64, minecraft:ocean) ? minecraft:sea_lantern"
    " : minecraft:polished_blackstone_bricks)}"
)

# 自然世界冒烟（配合 smoke-check-world.py --biome-smoke 7）：
# 噪声起伏地形（fbm2 定高）+ 交错洞穴（noise3 掏洞）+ y 变量写地层。
# 检查只看"地表材质方块都在"；地表起伏与洞穴空气率用 dump-biomes.py stats 复核。
NATURAL_FORMULA = (
    "{overworld="
    "let cont = fbm2(x, z, 180, 3, 1);"
    "let h = 64 + cont * 26;"
    "let cave = noise3(x, y, z, 70, 7);"
    "y=-64..319: (cave > 0.35 && y < h - 4) ? minecraft:air"
    " : (y <= h ? (y > h - 4 ? minecraft:grass_block : minecraft:stone) : minecraft:air)}"
)

# 雕刻器 A/B 冒烟（--carvers / --carvers-off；配合 smoke-check-world.py --biome-smoke 8）：
# 普通（噪声）世界里铺整块石头到 y=80，开/关原版雕刻器各跑一次，
# 用 dump-biomes.py stats 比较 y∈[10,70] 的空气率（开 = 有隧道/峡谷被挖出）。
CARVERS_FORMULA = (
    "{overworld=[carvers:vanilla] y=-64..319: y <= 80 ? minecraft:stone : minecraft:air}"
)
CARVERS_OFF_FORMULA = (
    "{overworld=[carvers:none] y=-64..319: y <= 80 ? minecraft:stone : minecraft:air}"
)

# 超平坦雕刻器 A/B 冒烟（--flat-carvers / --flat-carvers-off；flat_plus 预设）：
# 公式与上面相同，区别在 level-type——验证 1.2.6 超平坦世界的"干燥代理"雕刻
# （FlatLevelSource 的 applyCarvers 原本为空实现）。同样用 stats 比较空气率。
FLAT_CARVERS_FORMULA = CARVERS_FORMULA
FLAT_CARVERS_OFF_FORMULA = CARVERS_OFF_FORMULA

BIOME_STRUCTURES_FORMULA = (
    "{overworld=[biome:minecraft:desert] [structure:only=desert_pyramids] "
    "y=-64: minecraft:bedrock;y=-63..0: minecraft:stone;y=1..64: minecraft:air}"
)

# P4.3 特性冒烟：下界固定玄武岩三角洲（原始群系表内 → 装饰合法），
# all = 原版装饰（黑石/玄武岩斑块、荧石、岩浆块…）；none = 只留公式地形。
# 默认公式行不含装饰方块，核验靠 smoke-check-world.py --features-smoke。
FEATURES_ALL_FORMULA = (
    "{the_nether=[biome:minecraft:basalt_deltas] [features:all] "
    "y=0..60: minecraft:netherrack;y=61..127: minecraft:air}"
)

FEATURES_NONE_FORMULA = (
    "{the_nether=[biome:minecraft:basalt_deltas] [features:none] "
    "y=0..60: minecraft:netherrack;y=61..127: minecraft:air}"
)


def free_port():
    """向系统要一个当前空闲的端口（比按名字哈希取模可靠：多组验证并行时不会撞端口）。"""
    import socket
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    force = "--force-formula" in sys.argv
    seed_formula = "--seed-formula" in sys.argv
    spawn_formula = "--spawn-formula" in sys.argv
    dimension_formula = "--dimension-formula" in sys.argv
    dimension_alias = "--dimension-alias" in sys.argv
    marker_formula = "--marker-formula" in sys.argv
    structure_none = "--structure-none" in sys.argv
    structure_only = "--structure-only" in sys.argv
    biome_desert = "--biome-desert" in sys.argv
    biome_vanilla = "--biome-vanilla" in sys.argv
    biome_structures = "--biome-structures" in sys.argv
    biome_formula = "--biome-formula" in sys.argv
    biome_formula_fallback = "--biome-formula-fallback" in sys.argv
    biome_terrain = "--biome-terrain" in sys.argv
    biome_biomeis = "--biome-biomeis" in sys.argv
    natural = "--natural" in sys.argv
    carvers = "--carvers" in sys.argv
    carvers_off = "--carvers-off" in sys.argv
    flat_carvers = "--flat-carvers" in sys.argv
    flat_carvers_off = "--flat-carvers-off" in sys.argv
    features_all = "--features-all" in sys.argv
    features_none = "--features-none" in sys.argv
    if len(args) != 2:
        print(__doc__)
        return 2
    server = Path(args[0])
    # 端口传 "auto" 时由系统分配空闲端口
    port = str(free_port()) if args[1] == "auto" else args[1]
    server.mkdir(parents=True, exist_ok=True)

    (server / "eula.txt").write_text("eula=true\n", encoding="utf-8")

    props = {}
    prop_file = server / "server.properties"
    if prop_file.exists():
        for line in prop_file.read_text(encoding="utf-8", errors="replace").splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                key, _, value = line.partition("=")
                props[key.strip()] = value.strip()
    props.update(PROPERTIES)
    if carvers or carvers_off or spawn_formula:
        # 雕刻器只存在于噪声生成器：必须用普通世界类型。
        # 出生点专项同理：超平坦世界的出生点固定在 (0,0)，噪声世界的出生点随种子变化，
        # 才能区分"读到真实出生点"与"恒为 0"。
        props["level-type"] = "minecraft\\:normal"
    if structure_none or structure_only:
        # 结构冒烟要真正生成结构：
        # - 用我们自己的预设（结构覆盖表已移除 → 候选=全部结构）；
        # - 打开结构开关（默认冒烟是关结构，避免干扰方块核验）。
        props["level-type"] = "ohmyworld\\:flat_plus"
        props["generate-structures"] = "true"
    elif biome_desert or biome_vanilla or biome_structures or biome_formula or biome_formula_fallback \
            or biome_terrain or biome_biomeis or natural or features_all or features_none \
            or flat_carvers or flat_carvers_off:
        # 群系/特性/超平坦雕刻冒烟：用我们自己的预设（三维度齐全，下界为噪声生成器）。
        props["level-type"] = "ohmyworld\\:flat_plus"
    if biome_structures:
        # 结构解锁冒烟要真正生成结构
        props["generate-structures"] = "true"
    props["server-port"] = str(port)
    prop_file.write_text("\n".join(f"{k}={v}" for k, v in props.items()) + "\n", encoding="utf-8")

    if dimension_formula:
        formula = DIMENSION_FORMULA
    elif dimension_alias:
        formula = DIMENSION_ALIAS_FORMULA
    elif marker_formula:
        formula = DIMENSION_FORMULA
    elif structure_none:
        formula = STRUCTURE_NONE_FORMULA
    elif structure_only:
        formula = STRUCTURE_ONLY_FORMULA
    elif biome_desert:
        formula = BIOME_DESERT_FORMULA
    elif biome_vanilla:
        formula = BIOME_VANILLA_FORMULA
    elif biome_structures:
        formula = BIOME_STRUCTURES_FORMULA
    elif biome_formula:
        formula = BIOME_FORMULA_FORMULA
    elif biome_formula_fallback:
        formula = BIOME_FORMULA_FALLBACK_FORMULA
    elif biome_terrain:
        formula = BIOME_TERRAIN_FORMULA
    elif biome_biomeis:
        formula = BIOME_BIOMEIS_FORMULA
    elif natural:
        formula = NATURAL_FORMULA
    elif carvers:
        formula = CARVERS_FORMULA
    elif carvers_off:
        formula = CARVERS_OFF_FORMULA
    elif flat_carvers:
        formula = FLAT_CARVERS_FORMULA
    elif flat_carvers_off:
        formula = FLAT_CARVERS_OFF_FORMULA
    elif features_all:
        formula = FEATURES_ALL_FORMULA
    elif features_none:
        formula = FEATURES_NONE_FORMULA
    elif seed_formula:
        formula = SEED_FORMULA
    elif spawn_formula:
        formula = SPAWN_FORMULA
    else:
        formula = FORMULA

    config = server / "config" / "ohmyworld.json"
    wrote_formula = (force or seed_formula or spawn_formula or dimension_formula or dimension_alias
                     or marker_formula or structure_none or structure_only
                     or biome_desert or biome_vanilla or biome_structures
                     or biome_formula or biome_formula_fallback or biome_terrain or biome_biomeis or natural \
                     or carvers or carvers_off or flat_carvers or flat_carvers_off
                     or features_all or features_none
                     or not config.exists())
    if not wrote_formula:
        # 既有配置若本身就是「冒烟公式」（含特征方块），允许升级为本工具的最新版本；
        # 只有作者手写/巨构公式才原样保留。
        try:
            existing = config.read_text(encoding="utf-8")
            if any(marker in existing for marker in ("sea_lantern", "polished_blackstone_bricks", "white_concrete")):
                wrote_formula = True
        except Exception:
            pass
    if wrote_formula:
        config.parent.mkdir(parents=True, exist_ok=True)
        import os
        debug = "true" if os.environ.get("SMOKE_DEBUG") == "1" else "false"
        config.write_text(
            '{\n  "server_mode": %s,\n  "debug_logs": %s,\n  "formula": "%s"\n}\n'
            % ("false" if marker_formula else "true", debug, formula), encoding="utf-8"
        )

    if marker_formula:
        # 预置世界 marker：server_mode=false 时由 WorldLoadHandler 的 marker 恢复路径接管
        world = server / "world"
        world.mkdir(parents=True, exist_ok=True)
        (world / "ohmyworld_marker.txt").write_text(DIMENSION_FORMULA, encoding="utf-8")

    if marker_formula:
        label = "marker 驱动冒烟（预置分节 marker、server_mode=false）"
    elif dimension_formula:
        label = "使用分节冒烟公式（1：overworld+the_nether，末地缺失）"
    elif dimension_alias:
        label = "使用分节冒烟公式（2：无 overworld + the_end 别名）"
    elif structure_none:
        label = "使用结构冒烟公式（下界 [structure:none]）"
    elif structure_only:
        label = "使用结构冒烟公式（下界 [structure:only=nether_fossils]）"
    elif biome_desert:
        label = "使用群系冒烟公式（主世界 [biome:minecraft:desert]）"
    elif biome_vanilla:
        label = "使用群系冒烟公式（主世界 [biome:vanilla]）"
    elif biome_structures:
        label = "使用群系结构冒烟公式（主世界 [biome:desert] + [structure:only=desert_pyramids]）"
    elif biome_formula:
        label = "使用公式群系冒烟（主世界 biome 行两段全覆盖、fallback=none）"
    elif biome_formula_fallback:
        label = "使用公式群系冒烟（主世界 biome 行 + [biome-fallback:3d]）"
    elif biome_terrain:
        label = "使用地形联动冒烟（biome 行调用 terrain/surfis/blockis）"
    elif biome_biomeis:
        label = "使用群系回读冒烟（[biome:vanilla] + 方块层 biomeis）"
    elif natural:
        label = "使用自然世界冒烟（噪声地形 + 洞穴 + y 变量）"
    elif carvers:
        label = "使用雕刻器冒烟（普通世界 + [carvers:vanilla]）"
    elif carvers_off:
        label = "使用雕刻器对照冒烟（普通世界 + [carvers:none]）"
    elif flat_carvers:
        label = "使用超平坦雕刻器冒烟（flat_plus + [carvers:vanilla] 干燥代理）"
    elif flat_carvers_off:
        label = "使用超平坦雕刻器对照冒烟（flat_plus + [carvers:none]）"
    elif features_all:
        label = "使用特性冒烟公式（下界 [features:all] + 玄武岩三角洲）"
    elif features_none:
        label = "使用特性冒烟公式（下界 [features:none] + 玄武岩三角洲）"
    elif seed_formula:
        label = "使用种子专项冒烟公式"
    elif spawn_formula:
        label = "使用出生点专项冒烟公式（spawnx/spawnz 圆盘）"
    elif wrote_formula:
        label = "使用冒烟公式"
    else:
        label = "保留已有公式"
    print(f"[smoke-config] {server}: level-type=flat, port={port}, {label}")
    print(f"SMOKE_FORMULA={1 if wrote_formula else 0}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
