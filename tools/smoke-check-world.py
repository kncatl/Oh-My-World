#!/usr/bin/env python3
"""核验冒烟世界确实由本模组的公式生成（而不是原版地形）。

用法:
  smoke-check-world.py [--require-all] <服务端目录>
  smoke-check-world.py --dimension-smoke <1|2> <服务端目录>
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

--expect/--forbid：按维度核验任意方块（逗号分隔多个；维度名 overworld/
  the_nether/the_end）。1.21.x（region/DIM-1/DIM1）与 26.x（dimensions/...）
  两代目录布局都会检查。
"""

import sys
import zlib
from pathlib import Path

MARKERS = (b"minecraft:sea_lantern", b"minecraft:polished_blackstone_bricks")

HEADER_SECTORS = 2  # 前 8KB：4KB 偏移表 + 4KB 时间戳表

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


def parse_args(argv):
    """返回 (server, require_all, dimension_smoke, expects, forbids)；出错返回 None。"""
    server = None
    require_all = False
    dimension_smoke = None
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
    return server, require_all, dimension_smoke, expects, forbids


def scan(dirs, needles):
    """在 region 目录里解压区块，返回出现的 needle 集合与区块总数。"""
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
                chunks += 1
                for needle in needles:
                    if needle in raw:
                        found.add(needle)
    return found, chunks


def check_dimension(world, dimension, expect, forbid):
    """按维度核验；expect/forbid 为 bytes 元组。返回 (ok, 说明)。"""
    dirs = [world / rel for rel in DIMENSION_PATHS[dimension] if (world / rel).is_dir()]
    if not dirs:
        return False, f"{dimension}: 没有 region 目录"
    found, chunks = scan(dirs, tuple(expect) + tuple(forbid))
    if chunks == 0:
        return False, f"{dimension}: region 目录里没有区块"
    missing = [b.decode() for b in expect if b not in found]
    if missing:
        return False, f"{dimension}: 缺少 {missing}（已查 {chunks} 个区块）"
    unexpected = [b.decode() for b in forbid if b in found]
    if unexpected:
        return False, f"{dimension}: 出现了不应有的 {unexpected}（已查 {chunks} 个区块）"
    return True, f"{dimension}: {chunks} 个区块 OK"


def run_checks(world, expects, forbids):
    ok = True
    for dimension in sorted(set(expects) | set(forbids)):
        if dimension not in DIMENSION_PATHS:
            print(f"[smoke-check] FAIL: 未知维度 {dimension}（可用：overworld/the_nether/the_end）")
            return False
        expect = tuple(b.encode() for b in expects.get(dimension, ()))
        forbid = tuple(b.encode() for b in forbids.get(dimension, ()))
        dim_ok, message = check_dimension(world, dimension, expect, forbid)
        print(("[smoke-check] " + ("OK: " if dim_ok else "FAIL: ") + message))
        ok = ok and dim_ok
    return ok


def main():
    parsed = parse_args(sys.argv[1:])
    if parsed is None:
        return 2
    server, require_all, dimension_smoke, expects, forbids = parsed
    if server is None:
        print(__doc__)
        return 2
    world = Path(server) / "world"

    if dimension_smoke:
        rules = DIMENSION_SMOKE_RULES.get(dimension_smoke)
        if rules is None:
            print(f"[smoke-check] FAIL: 未知分节冒烟模式 {dimension_smoke}")
            return 2
        merged_expects = {dim: list(blocks) for dim, blocks in rules["expect"].items()}
        merged_forbids = {dim: list(blocks) for dim, blocks in rules.get("forbid", {}).items()}
        # forbid-only 的维度也要有区块（例如模式 2 的主世界）
        for dim in merged_forbids:
            merged_expects.setdefault(dim, [])
        return 0 if run_checks(world, merged_expects, merged_forbids) else 1

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
