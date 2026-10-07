#!/usr/bin/env python3
"""群系数据转储：直接读区块 NBT 里的 4×4×4 群系容器（不依赖游戏进程）。

用途：核实"世界里存下来的群系"到底是不是严格的 4×4×4、整列是否一致、
以及分布图案是否与公式一致。注意：F3 / 小地图的显示会叠加原版的
"模糊缩放"（BiomeManager），边界看起来不会正好落在 4 格线上——那是
显示层的行为（原版世界同样如此），不是存储层。

用法:
  dump-biomes.py column <世界/服务端目录> <x> <z>
      打印该列每个 4 高格（y 步进 4）的群系。
  dump-biomes.py grid <目录> <y> <x0> <z0> <x1> <z1>
      以 4 格为单元打印某 y 层的示意图（每格两个字符）。
  dump-biomes.py verify <目录>
      扫描全部区块：整列不一致的单元数 + 出现的群系统计。
  dump-biomes.py zoom <目录> <y> <x0> <z0> <x1> <z1> [种子]
      逐格复刻 F3 的显示（BiomeManager 模糊缩放）并与存储对比；
      种子省略时自动从 level.dat 读取。
  dump-biomes.py stats <目录> [y0 y1]
      全图体检：地表高度 min/max/均值/σ、方块统计、（可选）y 段空气率。

目录可给 versions/<节点>/run 或直接给 world 目录；自动识别两代
region 布局（1.21.x: world/region；26.x: world/dimensions/.../overworld/region）。
"""

import struct
import sys
import zlib
from pathlib import Path


# ---------------------------------------------------------------- NBT 解析

def _payload(data, i, tag):
    if tag == 1:
        return struct.unpack_from(">b", data, i)[0], i + 1
    if tag == 2:
        return struct.unpack_from(">h", data, i)[0], i + 2
    if tag == 3:
        return struct.unpack_from(">i", data, i)[0], i + 4
    if tag == 4:
        return struct.unpack_from(">q", data, i)[0], i + 8
    if tag == 5:
        return struct.unpack_from(">f", data, i)[0], i + 4
    if tag == 6:
        return struct.unpack_from(">d", data, i)[0], i + 8
    if tag == 7:  # byte array
        n = struct.unpack_from(">i", data, i)[0]
        return data[i + 4:i + 4 + n], i + 4 + n
    if tag == 8:  # string
        n = struct.unpack_from(">H", data, i)[0]
        return data[i + 2:i + 2 + n].decode("utf-8", "replace"), i + 2 + n
    if tag == 9:  # list
        element_type = data[i]
        n = struct.unpack_from(">i", data, i + 1)[0]
        i += 5
        items = []
        for _ in range(n):
            value, i = _payload(data, i, element_type)
            items.append(value)
        return items, i
    if tag == 10:  # compound
        out = {}
        while True:
            if data[i] == 0:
                return out, i + 1
            name, value, i = _entry(data, i)
            out[name] = value
    if tag == 11:  # int array
        n = struct.unpack_from(">i", data, i)[0]
        return list(struct.unpack_from(f">{n}i", data, i + 4)), i + 4 + 4 * n
    if tag == 12:  # long array
        n = struct.unpack_from(">i", data, i)[0]
        return struct.unpack_from(f">{n}Q", data, i + 4), i + 4 + 8 * n
    raise ValueError(f"未知 NBT tag {tag} @ {i}")


def _entry(data, i):
    tag = data[i]
    i += 1
    if tag == 0:
        return None, None, i
    n = struct.unpack_from(">H", data, i)[0]
    i += 2
    name = data[i:i + n].decode("utf-8", "replace")
    i += n
    value, i = _payload(data, i, tag)
    return name, value, i


def parse_chunk(raw):
    # 根节点：TAG_Compound + 名字（长度 2 字节）；payload 从名字之后开始
    i = 1
    name_len = struct.unpack_from(">H", raw, i)[0]
    i += 2 + name_len
    value, _ = _payload(raw, i, 10)
    return value


# ---------------------------------------------------------------- 群系容器

def decode_cells(biomes):
    """解出 64 个单元的调色板下标；顺序 (y<<4)|(z<<2)|x（x 最快）。"""
    palette = biomes.get("palette", [])
    data = biomes.get("data")
    if not data:
        return [0] * 64
    values = data
    count = len(values)
    size = len(palette)
    for bits in range(1, 9):
        per_long = 64 // bits
        need = (64 + per_long - 1) // per_long
        if need != count:
            continue
        mask = (1 << bits) - 1
        cells = []
        for idx in range(64):
            value = (values[idx // per_long] >> ((idx % per_long) * bits)) & mask
            if value >= size:
                cells = None
                break
            cells.append(value)
        if cells is not None:
            return cells
    raise ValueError(f"无法解码群系容器（palette={size}, longs={count}）")


def prepare(chunk):
    """给每个 section 挂上 _cells（解不动就 None）。"""
    for section in chunk.get("sections", []):
        if "biomes" in section:
            try:
                section["_cells"] = decode_cells(section["biomes"])
            except Exception:
                section["_cells"] = None


def cell_at(section, x, y, z):
    """section 内世界坐标 (x, y, z) 的群系 id。"""
    palette = section["biomes"]["palette"]
    bottom = section["Y"] * 16
    cx = (x >> 2) & 3
    cz = (z >> 2) & 3
    cy = ((y - bottom) >> 2) & 3
    return palette[section["_cells"][(cy << 4) | (cz << 2) | cx]]


def section_for(chunk, y):
    for section in chunk.get("sections", []):
        if section.get("_cells") is None:
            continue
        bottom = section["Y"] * 16
        if bottom <= y < bottom + 16:
            return section
    return None


# ---------------------------------------------------------------- 方块解码（stats 用）

AIR_NAMES = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}


def decode_block_cells(block_states):
    """解出 4096 个方块单元的调色板下标；顺序 (y<<8)|(z<<4)|x（x 最快）。"""
    palette = block_states.get("palette", [])
    data = block_states.get("data")
    if not data:
        return [0] * 4096
    count = len(data)
    for bits in range(4, 16):
        per_long = 64 // bits
        need = (4096 + per_long - 1) // per_long
        if need != count:
            continue
        mask = (1 << bits) - 1
        cells = []
        ok = True
        for idx in range(4096):
            value = (data[idx // per_long] >> ((idx % per_long) * bits)) & mask
            if value >= len(palette):
                ok = False
                break
            cells.append(value)
        if ok:
            return cells
    raise ValueError(f"无法解码方块容器（palette={len(palette)}, longs={count}）")


def prepare_blocks(chunk):
    for section in chunk.get("sections", []):
        if "block_states" in section:
            try:
                section["_blocks"] = decode_block_cells(section["block_states"])
            except Exception:
                section["_blocks"] = None


def palette_name(entry):
    """方块调色板条目：可能是字符串，也可能是带属性的复合（取 Name）。"""
    if isinstance(entry, dict):
        return entry.get("Name", "?")
    return entry


def block_at(section, lx, y, lz):
    """section 内本地坐标的方块 id（只取名，不带属性）。"""
    bottom = section["Y"] * 16
    palette = section["block_states"]["palette"]
    index = (((y - bottom) << 4) | lz) << 4 | lx
    return palette_name(palette[section["_blocks"][index]])


def cmd_stats(directory, band=None):
    """全图体检：地表起伏 + 方块统计 +（可选）y 段的精确空气率。"""
    import math
    heights = []
    block_counts = {}
    band_air = 0
    band_total = 0
    chunks = 0
    for cx, cz, chunk in load_chunks(directory):
        prepare_blocks(chunk)
        sections = [s for s in chunk.get("sections", []) if s.get("_blocks")]
        if not sections:
            continue
        # 只统计真的含地形的区块（未生成的区块是整块空气，会把统计拉偏）
        has_terrain = any(palette_name(s["block_states"]["palette"][v]) not in AIR_NAMES
                          for s in sections for v in s["_blocks"])
        if not has_terrain:
            continue
        chunks += 1
        ordered = sorted(sections, key=lambda s: s["Y"], reverse=True)
        for lx in range(16):
            for lz in range(16):
                found = None
                for section in ordered:
                    bottom = section["Y"] * 16
                    for y in range(bottom + 15, bottom - 1, -1):
                        if block_at(section, lx, y, lz) not in AIR_NAMES:
                            found = y
                            break
                    if found is not None:
                        break
                heights.append(found if found is not None else -999)
        for section in sections:
            bottom = section["Y"] * 16
            palette = section["block_states"]["palette"]
            for idx, value in enumerate(section["_blocks"]):
                name = palette_name(palette[value])
                block_counts[name] = block_counts.get(name, 0) + 1
                if band is not None:
                    y = bottom + (idx >> 8)
                    if band[0] <= y <= band[1]:
                        band_total += 1
                        if name in AIR_NAMES:
                            band_air += 1
    if not heights:
        print("没有可统计的区块")
        return
    real = [h for h in heights if h > -900]
    mean = sum(real) / len(real) if real else 0
    variance = sum((h - mean) ** 2 for h in real) / len(real) if real else 0
    print(f"区块 {chunks} 个，土地列 {len(real)} 个（空列 {len(heights) - len(real)}）")
    print(f"地表高度：min={min(real) if real else '-'} max={max(real) if real else '-'} "
          f"mean={mean:.1f} σ={math.sqrt(variance):.1f}")
    print("方块统计（前 10）：")
    for name, count in sorted(block_counts.items(), key=lambda kv: -kv[1])[:10]:
        print(f"  {count:8d}  {name}")
    if band is not None and band_total:
        print(f"y 段 [{band[0]}, {band[1]}] 内空气率：{band_air / band_total:.1%}")


# ---------------------------------------------------------------- 区块读取

def region_dirs(root):
    root = Path(root)
    candidates = []
    for world in (root, root / "world"):
        if not world.is_dir():
            continue
        # 1.21.x：world/region；26.x：world/dimensions/minecraft/overworld/region
        if (world / "region").is_dir():
            candidates.append(world / "region")
        dimensions = world / "dimensions"
        if dimensions.is_dir():
            candidates.extend(d for d in dimensions.rglob("region")
                              if d.is_dir() and "overworld" in str(d))
    if candidates:
        return candidates
    # 兜底：递归找 region 目录，优先主世界/最上层
    found = [d for d in root.rglob("region") if d.is_dir()]
    main = [d for d in found
            if "overworld" in str(d) or d.parent.name == root.name or d.parent.name == "world"]
    return main or found


def load_chunks(directory):
    for region in sorted(Path(directory).glob("*.mca")):
        try:
            _, rx, rz = region.stem.split(".")
            rx, rz = int(rx), int(rz)
        except ValueError:
            continue
        data = region.read_bytes()
        for i in range(1024):
            offset = int.from_bytes(data[i * 4:i * 4 + 3], "big")
            if offset == 0:
                continue
            start = offset * 4096
            if start + 5 > len(data):
                continue
            length = int.from_bytes(data[start:start + 4], "big")
            if data[start + 4] != 2 or length <= 1:
                continue
            try:
                raw = zlib.decompress(data[start + 5:start + 4 + length])
                chunk = parse_chunk(raw)
            except Exception:
                continue
            yield rx * 32 + i % 32, rz * 32 + i // 32, chunk


# ---------------------------------------------------------------- 模糊缩放复刻

M64 = (1 << 64) - 1
_LCG_MULT = 6364136223846793005
_LCG_INC = 1442695040888963407


def _s64(value):
    value &= M64
    return value - (1 << 64) if value >= (1 << 63) else value


def _lcg_next(seed, value):
    """net.minecraft.util.LinearCongruentialGenerator.next（64 位环绕）。"""
    return _s64(_s64(seed * _s64(seed * _LCG_MULT + _LCG_INC)) + value)


def _fiddle(value):
    d = (value >> 24) % 1024 / 1024.0
    return (d - 0.5) * 0.9


def _fiddled_distance(seed, x, y, z, dx, dy, dz):
    m = _lcg_next(seed, x)
    m = _lcg_next(m, y)
    m = _lcg_next(m, z)
    m = _lcg_next(m, x)
    m = _lcg_next(m, y)
    m = _lcg_next(m, z)
    g = _fiddle(m)
    m = _lcg_next(m, seed)
    h = _fiddle(m)
    m = _lcg_next(m, seed)
    n = _fiddle(m)
    return (dz + n) ** 2 + (dy + h) ** 2 + (dx + g) ** 2


def obfuscate_seed(seed):
    """BiomeManager.obfuscateSeed：Guava sha256 + HashCode.asLong（小端）。"""
    import hashlib
    digest = hashlib.sha256(_s64(seed).to_bytes(8, "little")).digest()
    return _s64(int.from_bytes(digest[:8], "little"))


def zoom_quart(biome_zoom_seed, x, y, z):
    """复刻 BiomeManager.getBiome：返回被选中的 quart 单元（fx, fy, fz）。"""
    i, j, k = x - 2, y - 2, z - 2
    qx, qy, qz = i >> 2, j >> 2, k >> 2
    dx, dy, dz = (i & 3) / 4.0, (j & 3) / 4.0, (k & 3) / 4.0
    best, best_distance = None, float("inf")
    for corner in range(8):
        pick_x = qx if (corner & 4) == 0 else qx + 1
        pick_y = qy if (corner & 2) == 0 else qy + 1
        pick_z = qz if (corner & 1) == 0 else qz + 1
        ox = dx if (corner & 4) == 0 else dx - 1.0
        oy = dy if (corner & 2) == 0 else dy - 1.0
        oz = dz if (corner & 1) == 0 else dz - 1.0
        distance = _fiddled_distance(biome_zoom_seed, pick_x, pick_y, pick_z, ox, oy, oz)
        if distance < best_distance:
            best_distance, best = distance, (pick_x, pick_y, pick_z)
    return best


# ---------------------------------------------------------------- 命令

def cmd_column(directory, x, z):
    print(f"# 列 x={x} z={z}")
    for cx, cz, chunk in load_chunks(directory):
        if not (cx * 16 <= x < cx * 16 + 16 and cz * 16 <= z < cz * 16 + 16):
            continue
        prepare(chunk)
        for section in sorted((s for s in chunk["sections"] if s.get("_cells")),
                              key=lambda s: s["Y"]):
            bottom = section["Y"] * 16
            for cy in range(4):
                y = bottom + cy * 4
                print(f"  y={y:5d}..{y + 3:<5d} {cell_at(section, x, y, z)}")
        return
    print("(没找到包含该坐标的区块)")


def cmd_grid(directory, y, x0, z0, x1, z1):
    print(f"# y={y} 的 4 格单元图：x {x0}..{x1}, z {z0}..{z1}")
    chunks = {}
    for cx, cz, chunk in load_chunks(directory):
        prepare(chunk)
        chunks[(cx, cz)] = chunk

    def short(name):
        return name.replace("minecraft:", "")[:2]

    print("        " + " ".join(f"{x // 4 % 100:02d}" for x in range(x0, x1 + 1, 4)))
    for z in range(z0, z1 + 1, 4):
        row = []
        for x in range(x0, x1 + 1, 4):
            chunk = chunks.get((x // 16, z // 16))
            section = section_for(chunk, y) if chunk else None
            row.append(short(cell_at(section, x, y, z)) if section else "??")
        print(f"z={z:5d} " + " ".join(row))


def cmd_verify(directory):
    chunk_count = 0
    columns = 0
    non_uniform = 0
    biomes = {}
    for cx, cz, chunk in load_chunks(directory):
        prepare(chunk)
        sections = sorted((s for s in chunk["sections"] if s.get("_cells")),
                          key=lambda s: s["Y"])
        if not sections:
            continue
        chunk_count += 1
        for lx in range(4):
            for lz in range(4):
                values = {}
                for section in sections:
                    bottom = section["Y"] * 16
                    for cy in range(4):
                        y = bottom + cy * 4
                        values.setdefault(cell_at(section, cx * 16 + lx * 4, y,
                                                  cz * 16 + lz * 4), 0)
                        values[cell_at(section, cx * 16 + lx * 4, y,
                                       cz * 16 + lz * 4)] += 1
                columns += 1
                if len(values) > 1:
                    non_uniform += 1
        for section in sections:
            palette = section["biomes"]["palette"]
            for value in section["_cells"]:
                name = palette[value]
                biomes[name] = biomes.get(name, 0) + 1
    print(f"区块 {chunk_count} 个：列单元 {columns} 个，整列不一致 {non_uniform} 个")
    print("群系统计（按单元数）：")
    for name, count in sorted(biomes.items(), key=lambda kv: -kv[1]):
        print(f"  {count:6d}  {name}")


def world_seed(directory):
    """从存档的 level.dat 读世界种子（zoom 模式用）。"""
    import gzip
    level_dat = Path(directory).parent / "level.dat"
    data = level_dat.read_bytes()
    if data[:2] == b"\x1f\x8b":
        data = gzip.decompress(data)
    root = parse_chunk(data)["Data"]
    settings = root.get("WorldGenSettings")
    if isinstance(settings, dict) and "seed" in settings:
        return settings["seed"]
    return root.get("RandomSeed")


def cmd_zoom(directory, seed, y, x0, z0, x1, z1):
    if seed is None:
        seed = world_seed(directory)
    chunks = {}
    for cx, cz, chunk in load_chunks(directory):
        prepare(chunk)
        chunks[(cx, cz)] = chunk
    zoom_seed = obfuscate_seed(seed)

    def stored(x, z):
        chunk = chunks.get((x // 16, z // 16))
        section = section_for(chunk, y) if chunk else None
        return cell_at(section, x, y, z) if section else None

    def displayed(x, z):
        qx, qy, qz = zoom_quart(zoom_seed, x, y, z)
        chunk = chunks.get(((qx << 2) // 16, (qz << 2) // 16))
        section = section_for(chunk, qy << 2) if chunk else None
        return cell_at(section, qx << 2, qy << 2, qz << 2) if section else None

    def short(name):
        return name.replace("minecraft:", "")[:2] if name else "??"

    print(f"# y={y}：逐格打印 F3 会显示的群系（BiomeManager 模糊缩放选中单元）")
    print("        " + " ".join(f"{x % 100:02d}" for x in range(x0, x1 + 1)))
    diff = 0
    total = 0
    for z in range(z0, z1 + 1):
        row = []
        for x in range(x0, x1 + 1):
            display, store = displayed(x, z), stored(x, z)
            row.append(short(display))
            total += 1
            if display != store:
                diff += 1
        print(f"z={z:5d} " + " ".join(row))
    print(f"# 显示与存储不同的位置：{diff}/{total}")

    worst, worst_at = 0, (x0, z0)
    for cx in range(x0 & ~3, x1 + 1, 4):
        for cz in range(z0 & ~3, z1 + 1, 4):
            seen = {displayed(x, z) for x in range(cx, cx + 4) for z in range(cz, cz + 4)}
            if len(seen) > worst:
                worst, worst_at = len(seen), (cx, cz)
    print(f"# 单个 4×4 区域内显示出的不同群系数最大值：{worst}"
          f"（x={worst_at[0]} z={worst_at[1]}）")


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 2
    mode, root = argv[1], argv[2]
    dirs = region_dirs(root)
    if not dirs:
        print(f"找不到主世界 region 目录（{root}）")
        return 1
    directory = dirs[0]
    if mode == "column" and len(argv) == 5:
        cmd_column(directory, int(argv[3]), int(argv[4]))
    elif mode == "grid" and len(argv) == 8:
        cmd_grid(directory, int(argv[3]), int(argv[4]), int(argv[5]),
                 int(argv[6]), int(argv[7]))
    elif mode == "zoom" and len(argv) in (8, 9):
        seed = int(argv[8]) if len(argv) == 9 else None
        cmd_zoom(directory, seed, int(argv[3]), int(argv[4]), int(argv[5]),
                 int(argv[6]), int(argv[7]))
    elif mode == "stats" and len(argv) in (3, 5):
        band = (int(argv[3]), int(argv[4])) if len(argv) == 5 else None
        cmd_stats(directory, band)
    elif mode == "verify" and len(argv) == 3:
        cmd_verify(directory)
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
