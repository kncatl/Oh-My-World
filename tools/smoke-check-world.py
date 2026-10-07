#!/usr/bin/env python3
"""核验冒烟世界确实由本模组的公式生成（而不是原版地形）。

用法:
  smoke-check-world.py [--require-all] <服务端目录>
  smoke-check-world.py --dimension-smoke <1|2> <服务端目录>
  smoke-check-world.py --structure-smoke <1|2> <服务端目录>
  smoke-check-world.py --biome-smoke <1|2> <服务端目录>
  smoke-check-world.py --features-smoke <1|2> <服务端目录>
  smoke-check-world.py [--expect <维度> <方块,方块...>] [--forbid <维度> <方块,方块...>]... <服务端目录>

默认模式：扫描 world/ 下所有 region 目录，要求找到冒烟公式的特征方块
（minecraft:sea_lantern / minecraft:polished_blackstone_bricks）。原版平坦世界只会
有 bedrock/dirt/grass 之类，找不到它们 —— 因此这能证明「Mixin → fillChunk → 公式」
这条链路真的跑过，避免「服务器起来了但公式没生效」的假阳性。
--require-all：要求全部特征方块都出现（seed 冒烟用：seedhash 若退化成常量，
只会出现其中一种，必须判失败）。

--dimension-smoke：分节冒烟专用预设（配合 smoke-server-config.py 的同名模式）：
  1 = {overworld=...,the_nether=...} —— 末地缺失，必须保持原版（末地石）；
  2 = {the_nether=...,the_end=the_nether} —— 别名；主世界无公式 = 原版平坦。
  要求三个维度的 region 目录都有区块（服务器上先 forceload 下界/末地）。

--biome-smoke（P4.2）：群系冒烟预设：
  1 = [biome:minecraft:desert] —— 主世界只应有沙漠、无平原；
  2 = [biome:vanilla] —— 至少出现 2 种不同主世界群系（不再是清一色平原）；
  3 = biome 行两段全覆盖（深暗之域/沙漠）—— 两行都生效且无原版平原；
  4 = biome 行 + [biome-fallback:3d] —— 低段深暗之域 + 至少 2 种原版群系；
  5 = biome 行按 terrain/surfis/blockis 分布 —— 冰刺之地/沙漠/恶地都要出现；
  6 = [biome:vanilla] + 方块层 biomeis —— 两种"按群系铺的方块"都要出现；
  7 = 自然世界（1.2.5：噪声地形 + 洞穴 + y）—— 地表材质方块都要出现。

--features-smoke（P4.3）：装饰特性冒烟预设（下界固定玄武岩三角洲）：
  1 = [features:all] —— 装饰方块（黑石/荧石/岩浆块至少一种）应出现；
  2 = [features:none] —— 这些装饰方块一个都不应出现（只留公式地形）。

--expect/--forbid：按维度核验任意方块（逗号分隔多个；维度名 overworld/
  the_nether/the_end）。1.21.x（region/DIM-1/DIM1）与 26.x（dimensions/...）
  两代目录布局都会检查。
"""

import sys
import zlib
import re
from pathlib import Path

MARKERS = (b"minecraft:sea_lantern", b"minecraft:polished_blackstone_bricks")

HEADER_SECTORS = 2  # 前 8KB：4KB 偏移表 + 4KB 时间戳表

# 到达 BIOMES 阶段之前的区块状态：这些区块的分节群系调色板还是默认值
# （原版 PalettedContainerFactory 的默认群系 = minecraft:plains），扫描群系数据时跳过。
PRE_BIOME_STATUSES = (b"minecraft:empty", b"minecraft:structure_starts", b"minecraft:structure_references")

# 维度 → world 下的候选 region 目录（1.21.x / 26.x 两代布局）
DIMENSION_PATHS = {
    "overworld": ("region", "dimensions/minecraft/overworld/region"),
    "the_nether": ("DIM-1/region", "dimensions/minecraft/the_nether/region"),
    "the_end": ("DIM1/region", "dimensions/minecraft/the_end/region"),
}

DIMENSION_SMOKE_RULES = {
    "1": {
        "expect": {
            "overworld": ("minecraft:sea_lantern", "minecraft:polished_blackstone_bricks"),
            "the_nether": ("minecraft:ochre_froglight",),
            "the_end": ("minecraft:end_stone",),
        },
        "forbid": {
            "the_end": ("minecraft:ochre_froglight", "minecraft:sea_lantern",
                        "minecraft:polished_blackstone_bricks"),
        },
    },
    "2": {
        "expect": {
            "the_nether": ("minecraft:ochre_froglight",),
            "the_end": ("minecraft:ochre_froglight",),
        },
        "forbid": {
            "overworld": ("minecraft:ochre_froglight", "minecraft:sea_lantern",
                          "minecraft:polished_blackstone_bricks"),
            "the_end": ("minecraft:sea_lantern", "minecraft:polished_blackstone_bricks"),
        },
    },
}

# P4 结构冒烟（配合 smoke-server-config.py 的 --structure-none / --structure-only 与
# jar-server-smoke.sh 的 16×16 区块 forceload 网格）：
# 结构引用以结构 ID 的字符串形式保存在区块 NBT 里，直接按字节扫描即可。
# 用废弃矿井当白名单样本（密度高、平原群系有效）。
STRUCTURE_SMOKE_RULES = {
    "1": {  # [structure:none]：主世界不应出现任何结构
        "expect": {},
        "forbid": {
            "overworld": ("minecraft:mineshaft", "minecraft:village_plains", "minecraft:stronghold"),
        },
    },
    "2": {  # [structure:only=mineshafts]：必须有废弃矿井、且没有村庄/要塞
        "expect": {"overworld": ("minecraft:mineshaft",)},
        "forbid": {
            "overworld": ("minecraft:village_plains", "minecraft:stronghold"),
        },
    },
    "3": {  # [biome:desert] + [structure:only=desert_pyramids]：群系表重建后沙漠神殿应解锁
        "expect": {"overworld": ("minecraft:desert_pyramid",)},
    },
}

# P4.2 群系冒烟（配合 smoke-server-config.py --biome-desert / --biome-vanilla）：
# 群系 id 保存在区块 NBT 的群系调色板里，直接字节扫描。
BIOME_SMOKE_RULES = {
    "1": {  # [biome:minecraft:desert]：整片主世界只应有沙漠
        "expect": {"overworld": ("minecraft:desert",)},
        "forbid": {"overworld": ("minecraft:plains",)},
    },
    "2": {  # [biome:vanilla]：至少 2 种候选主世界群系 + 装饰特性照常（至少 1 种装饰方块）
        "expect": {},
        "expect_any_min": {
            "overworld": [
                (2, ("minecraft:badlands", "minecraft:bamboo_jungle", "minecraft:beach",
                 "minecraft:birch_forest", "minecraft:cherry_grove", "minecraft:cold_ocean",
                 "minecraft:dark_forest", "minecraft:deep_cold_ocean", "minecraft:deep_dark",
                 "minecraft:deep_frozen_ocean", "minecraft:deep_lukewarm_ocean",
                 "minecraft:deep_ocean", "minecraft:desert", "minecraft:dripstone_caves",
                 "minecraft:eroded_badlands", "minecraft:flower_forest", "minecraft:forest",
                 "minecraft:frozen_ocean", "minecraft:frozen_peaks", "minecraft:frozen_river",
                 "minecraft:grove", "minecraft:ice_spikes", "minecraft:jagged_peaks",
                 "minecraft:jungle", "minecraft:lukewarm_ocean", "minecraft:lush_caves",
                 "minecraft:mangrove_swamp", "minecraft:meadow", "minecraft:mushroom_fields",
                 "minecraft:ocean", "minecraft:old_growth_birch_forest",
                 "minecraft:old_growth_pine_taiga", "minecraft:old_growth_spruce_taiga",
                 "minecraft:plains", "minecraft:river", "minecraft:savanna",
                 "minecraft:savanna_plateau", "minecraft:snowy_beach", "minecraft:snowy_plains",
                 "minecraft:snowy_slopes", "minecraft:snowy_taiga", "minecraft:sparse_jungle",
                 "minecraft:stony_peaks", "minecraft:stony_shore", "minecraft:sunflower_plains",
                 "minecraft:swamp", "minecraft:taiga", "minecraft:warm_ocean",
                 "minecraft:windswept_forest", "minecraft:windswept_gravelly_hills",
                 "minecraft:windswept_hills", "minecraft:windswept_savanna",
                 "minecraft:wooded_badlands")),
                (1, ("minecraft:coal_ore", "minecraft:iron_ore", "minecraft:copper_ore",
                     "minecraft:oak_log", "minecraft:short_grass", "minecraft:poppy",
                     "minecraft:dandelion", "minecraft:cactus", "minecraft:dead_bush",
                     "minecraft:sugar_cane", "minecraft:sunflower")),
            ],
        },
    },
}

# 公式群系冒烟（配合 smoke-server-config.py --biome-formula / --biome-formula-fallback）：
# 3 = 两段全覆盖：低段深暗之域 + 高段沙漠都应出现（两行都生效），且不能出现原版平原；
# 4 = 3d 回退：低段深暗之域（公式行）+ 至少 2 种候选原版群系（回退行；候选表同模式 2）。
BIOME_SMOKE_RULES["3"] = {
    "expect": {"overworld": ("minecraft:deep_dark", "minecraft:desert")},
    "forbid": {"overworld": ("minecraft:plains",)},
}
BIOME_SMOKE_RULES["4"] = {
    "expect": {"overworld": ("minecraft:deep_dark",)},
    # 只核对"回退出来的是原版分布"（候选群系 ≥2）；装饰方块不在本模式判定
    # （超平坦基座没有草/沙等基底，植被类特性本来就放不下）。
    "expect_any_min": {
        "overworld": [BIOME_SMOKE_RULES["2"]["expect_any_min"]["overworld"][0]],
    },
}
# 5 = 地形联动（配合 smoke-server-config.py --biome-terrain）：biome 行用
# terrain/surfis/blockis 决定群系——三类群系都要出现，且不能有原版平原。
BIOME_SMOKE_RULES["5"] = {
    "expect": {"overworld": ("minecraft:ice_spikes", "minecraft:desert", "minecraft:badlands")},
    "forbid": {"overworld": ("minecraft:plains",)},
}
# 6 = [biome:vanilla] + 方块层 biomeis（配合 --biome-biomeis）：海洋列应铺海晶灯；
# 沙漠不在出生区——金块必须不出现（否则说明 biomeis 恒真）。
BIOME_SMOKE_RULES["6"] = {
    "expect": {"overworld": ("minecraft:sea_lantern", "minecraft:ocean")},
    "forbid": {"overworld": ("minecraft:gold_block",)},
}
# 7 = 自然世界（配合 smoke-server-config.py --natural）：噪声地形 + 洞穴 + y 变量。
BIOME_SMOKE_RULES["7"] = {
    "expect": {"overworld": ("minecraft:grass_block", "minecraft:stone")},
}

# P4.3 特性冒烟（配合 smoke-server-config.py --features-all / --features-none）：
# 下界固定玄武岩三角洲的装饰方块样本（公式地形只有下界岩，故这些方块只能来自装饰）。
FEATURE_NEEDLES = ("minecraft:blackstone", "minecraft:glowstone", "minecraft:magma_block")

FEATURES_SMOKE_RULES = {
    "1": {  # [features:all]：装饰应出现（至少一种样本方块）+ 群系确实换成了玄武岩三角洲
        "expect": {"the_nether": ("minecraft:netherrack", "minecraft:basalt_deltas")},
        "expect_any_min": {"the_nether": (1, FEATURE_NEEDLES)},
    },
    "2": {  # [features:none]：只留公式地形，装饰方块一个都不应出现
        "expect": {"the_nether": ("minecraft:netherrack", "minecraft:basalt_deltas")},
        "forbid": {"the_nether": FEATURE_NEEDLES},
    },
}


def parse_args(argv):
    """返回 (server, require_all, dimension_smoke, structure_smoke, biome_smoke,
    features_smoke, expects, forbids)；出错返回 None。"""
    server = None
    require_all = False
    dimension_smoke = None
    structure_smoke = None
    biome_smoke = None
    features_smoke = None
    expects = {}
    forbids = {}
    i = 0
    while i < len(argv):
        arg = argv[i]
        if arg == "--require-all":
            require_all = True
            i += 1
        elif arg == "--dimension-smoke" and i + 1 < len(argv):
            dimension_smoke = argv[i + 1]
            i += 2
        elif arg == "--structure-smoke" and i + 1 < len(argv):
            structure_smoke = argv[i + 1]
            i += 2
        elif arg == "--biome-smoke" and i + 1 < len(argv):
            biome_smoke = argv[i + 1]
            i += 2
        elif arg == "--features-smoke" and i + 1 < len(argv):
            features_smoke = argv[i + 1]
            i += 2
        elif arg in ("--expect", "--forbid") and i + 2 < len(argv):
            target = expects if arg == "--expect" else forbids
            target.setdefault(argv[i + 1], []).extend(argv[i + 2].split(","))
            i += 3
        elif arg.startswith("--"):
            print(f"[smoke-check] FAIL: 未知参数 {arg}")
            return None
        elif server is None:
            server = arg
            i += 1
        else:
            print("[smoke-check] FAIL: 多余的位置参数")
            return None
    return (server, require_all, dimension_smoke, structure_smoke, biome_smoke,
            features_smoke, expects, forbids)


def chunk_status(raw):
    """取区块 NBT 的 Status 字符串（找不到返回 None）。"""
    m = re.search(rb'\x08\x00\x06Status', raw)
    if not m:
        return None
    p = m.end()
    if p + 2 > len(raw):
        return None
    slen = int.from_bytes(raw[p:p + 2], "big")
    if p + 2 + slen > len(raw):
        return None
    return raw[p + 2:p + 2 + slen]


def scan(dirs, needles, filled_only=False):
    """在 region 目录里解压区块，返回出现的 needle 集合与区块总数。

    filled_only=True 时跳过尚未到达 BIOMES 阶段的区块（其分节群系调色板是
    默认值 minecraft:plains，会把群系核验带偏）。
    """
    found = set()
    chunks = 0
    for directory in dirs:
        for region in sorted(directory.glob("*.mca")):
            data = region.read_bytes()
            if len(data) < HEADER_SECTORS * 4096:
                continue
            for i in range(1024):
                offset = int.from_bytes(data[i * 4:i * 4 + 3], "big")
                if offset == 0:
                    continue
                start = offset * 4096
                if start + 5 > len(data):
                    continue
                length = int.from_bytes(data[start:start + 4], "big")
                # 压缩类型 2 = zlib（区域文件默认）
                if data[start + 4] != 2 or length <= 1:
                    continue
                try:
                    raw = zlib.decompress(data[start + 5:start + 4 + length])
                except Exception:
                    continue
                if filled_only:
                    status = chunk_status(raw)
                    if status is None or any(status.startswith(s) for s in PRE_BIOME_STATUSES):
                        continue
                chunks += 1
                for needle in needles:
                    if needle in raw:
                        found.add(needle)
    return found, chunks


def check_dimension(world, dimension, expect, forbid, any_groups=(), filled_only=False):
    """按维度核验；expect/forbid 为 bytes 元组，any_groups = ((至少几种, 候选元组), ...)。"""
    dirs = [world / rel for rel in DIMENSION_PATHS[dimension] if (world / rel).is_dir()]
    if not dirs:
        return False, f"{dimension}: 没有 region 目录"
    needles = list(expect) + list(forbid)
    for _, group in any_groups:
        needles.extend(group)
    found, chunks = scan(dirs, tuple(needles), filled_only=filled_only)
    if chunks == 0:
        return False, f"{dimension}: region 目录里没有区块"
    missing = [b.decode() for b in expect if b not in found]
    if missing:
        return False, f"{dimension}: 缺少 {missing}（已查 {chunks} 个区块）"
    unexpected = [b.decode() for b in forbid if b in found]
    if unexpected:
        return False, f"{dimension}: 出现了不应有的 {unexpected}（已查 {chunks} 个区块）"
    all_hits = []
    for min_any, group in any_groups:
        hits = [b.decode() for b in group if b in found]
        if len(hits) < min_any:
            return False, (f"{dimension}: 需要至少 {min_any} 种"
                           f"{[b.decode() for b in group]}，只找到 {hits}（已查 {chunks} 个区块）")
        all_hits.extend(hits)
    extra = f"（含 {all_hits}）" if any_groups else ""
    return True, f"{dimension}: {chunks} 个区块 OK{extra}"


def normalize_any_groups(raw):
    """把 expect_any_min 的单组 (min, needles) 或组列表统一成 ((min, bytes元组), ...)。"""
    if not raw:
        return []
    groups = [raw] if isinstance(raw[0], int) else list(raw)
    return [(g[0], tuple(b.encode() for b in g[1])) for g in groups]


def run_checks(world, expects, forbids, any_rules=None, filled_only=False):
    ok = True
    any_rules = any_rules or {}
    for dimension in sorted(set(expects) | set(forbids) | set(any_rules)):
        if dimension not in DIMENSION_PATHS:
            print(f"[smoke-check] FAIL: 未知维度 {dimension}（可用：overworld/the_nether/the_end）")
            return False
        expect = tuple(b.encode() for b in expects.get(dimension, ()))
        forbid = tuple(b.encode() for b in forbids.get(dimension, ()))
        groups = normalize_any_groups(any_rules.get(dimension))
        dim_ok, message = check_dimension(world, dimension, expect, forbid, groups,
                                          filled_only=filled_only)
        print(("[smoke-check] " + ("OK: " if dim_ok else "FAIL: ") + message))
        ok = ok and dim_ok
    return ok


def main():
    parsed = parse_args(sys.argv[1:])
    if parsed is None:
        return 2
    (server, require_all, dimension_smoke, structure_smoke, biome_smoke,
     features_smoke, expects, forbids) = parsed
    if server is None:
        print(__doc__)
        return 2
    world = Path(server) / "world"

    if dimension_smoke or structure_smoke or biome_smoke or features_smoke:
        if dimension_smoke:
            rules = DIMENSION_SMOKE_RULES.get(dimension_smoke)
        elif structure_smoke:
            rules = STRUCTURE_SMOKE_RULES.get(structure_smoke)
        elif biome_smoke:
            rules = BIOME_SMOKE_RULES.get(biome_smoke)
        else:
            rules = FEATURES_SMOKE_RULES.get(features_smoke)
        if rules is None:
            print("[smoke-check] FAIL: 未知冒烟预设 "
                  f"{dimension_smoke or structure_smoke or biome_smoke or features_smoke}")
            return 2
        merged_expects = {dim: list(blocks) for dim, blocks in rules["expect"].items()}
        merged_forbids = {dim: list(blocks) for dim, blocks in rules.get("forbid", {}).items()}
        # forbid/any 的维度也要有区块（例如模式 2 的主世界）
        for dim in merged_forbids:
            merged_expects.setdefault(dim, [])
        any_rules = rules.get("expect_any_min", {})
        for dim in any_rules:
            merged_expects.setdefault(dim, [])
        # 群系冒烟只统计已过 BIOMES 阶段的区块（半成品区块的默认调色板是 plains）
        return 0 if run_checks(world, merged_expects, merged_forbids, any_rules,
                               filled_only=biome_smoke is not None) else 1

    if expects or forbids:
        return 0 if run_checks(world, expects, forbids) else 1

    # 默认模式：整个 world 下找特征方块（兼容两代布局）
    region_dirs = sorted(p for p in world.rglob("region") if p.is_dir())
    if not region_dirs:
        print("[smoke-check] FAIL: 没有 region 目录")
        return 1
    found, chunks = scan(region_dirs, MARKERS)
    missing = [m.decode() for m in MARKERS if m not in found]
    if not found or (require_all and missing):
        extra = f"，缺少 {missing}" if missing else ""
        print(f"[smoke-check] FAIL: 在 {chunks} 个区块里特征方块不满足要求"
              f"（找到 {sorted(found)}{extra}）—— 公式很可能没有生效")
        return 1
    print(f"[smoke-check] OK: 在 {chunks} 个区块里找到 {', '.join(sorted(m.decode() for m in found))}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
