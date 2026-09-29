#!/usr/bin/env python3
"""核验冒烟世界确实由本模组的公式生成（而不是原版地形）。

用法: smoke-check-world.py <服务端目录>

做法：解压 region 文件里的区块数据（zlib），在方块调色板里找冒烟公式的特征方块
（minecraft:sea_lantern / minecraft:polished_blackstone_bricks）。原版平坦世界只会有
bedrock/dirt/grass 之类，找不到它们 —— 因此这能证明「Mixin → fillChunk → 公式」这条
链路真的跑过，避免「服务器起来了但公式没生效」的假阳性。
"""

import sys
import zlib
from pathlib import Path

MARKERS = (b"minecraft:sea_lantern", b"minecraft:polished_blackstone_bricks")

HEADER_SECTORS = 2  # 前 8KB：4KB 偏移表 + 4KB 时间戳表


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    region_dir = Path(sys.argv[1]) / "world" / "region"
    if not region_dir.is_dir():
        print("[smoke-check] FAIL: 没有 region 目录")
        return 1

    found = set()
    chunks = 0
    for region in sorted(region_dir.glob("*.mca")):
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
            for marker in MARKERS:
                if marker in raw:
                    found.add(marker.decode())

    if not found:
        print(f"[smoke-check] FAIL: 在 {chunks} 个区块里没有找到公式特征方块 —— 公式很可能没有生效")
        return 1
    print(f"[smoke-check] OK: 在 {chunks} 个区块里找到 {', '.join(sorted(found))}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
