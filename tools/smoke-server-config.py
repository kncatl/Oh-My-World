#!/usr/bin/env python3
"""为冒烟测试写服务端配置（开发服与真 jar 验证共用）。

用法: smoke-server-config.py <服务端目录> <端口> [--force-formula] [--seed-formula]

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
    props["server-port"] = str(port)
    prop_file.write_text("\n".join(f"{k}={v}" for k, v in props.items()) + "\n", encoding="utf-8")

    config = server / "config" / "ohmyworld.json"
    wrote_formula = force or seed_formula or not config.exists()
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
        config.write_text(
            '{\n  "server_mode": true,\n  "formula": "%s"\n}\n'
            % (SEED_FORMULA if seed_formula else FORMULA), encoding="utf-8"
        )

    print(f"[smoke-config] {server}: level-type=flat, port={port}, "
          + ("使用种子专项冒烟公式" if seed_formula else
             ("使用冒烟公式" if wrote_formula else "保留已有公式")))
    print(f"SMOKE_FORMULA={1 if wrote_formula else 0}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
