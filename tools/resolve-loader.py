#!/usr/bin/env python3
"""解析目标 MC 版本对应的加载器版本（供 tools/jar-server-smoke.sh 使用）。

用法:
  resolve-loader.py neoforge-version <mc>   # 该 MC 的 NeoForge 版本（优先稳定版，无稳定则 beta）
  resolve-loader.py fabric-api <mc>         # 该 MC 的 Fabric API 最新版
  resolve-loader.py fabric-installer        # fabric-installer 最新版

元数据缓存到 ~/.cache/ohmyworld-loader-metadata/，避免重复联网。
网络走环境变量里的代理（http_proxy/https_proxy）。
"""

import re
import sys
import urllib.request
from pathlib import Path

CACHE = Path.home() / ".cache" / "ohmyworld-loader-metadata"

SOURCES = {
    "neoforge": "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml",
    "fabric-api": "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml",
    "fabric-installer": "https://maven.fabricmc.net/net/fabricmc/fabric-installer/maven-metadata.xml",
}


def versions_of(key):
    """拉取（并缓存）maven-metadata.xml，返回按文件顺序的版本列表。"""
    CACHE.mkdir(parents=True, exist_ok=True)
    cached = CACHE / f"{key}.xml"
    if not cached.exists():
        with urllib.request.urlopen(SOURCES[key], timeout=60) as resp:
            cached.write_bytes(resp.read())
    text = cached.read_text(encoding="utf-8")
    return re.findall(r"<version>([^<]+)</version>", text)


def version_key(v):
    """粗粒度版本排序键：数字段逐个比较，非数字段（beta/rc）排在稳定版之后。"""
    parts = re.split(r"[.\-]", v)
    key = []
    for p in parts:
        if p.isdigit():
            key.append((0, int(p), ""))
        else:
            key.append((1, 0, p))
    return key


def neoforge_version(mc):
    # MC 1.21.x -> NeoForge 版本线 21.x；MC 26.x -> 26.x
    line = mc[2:] if mc.startswith("1.21") else mc
    candidates = [v for v in versions_of("neoforge") if v.startswith(line + ".")]
    if not candidates:
        raise SystemExit(f"NeoForge 没有与 MC {mc} 匹配的版本线（{line}.x）")
    stable = [v for v in candidates if "-" not in v]
    pool = stable or candidates
    return max(pool, key=version_key)


def fabric_api(mc):
    candidates = [v for v in versions_of("fabric-api") if v.endswith("+" + mc)]
    if not candidates:
        raise SystemExit(f"Fabric API 没有与 MC {mc} 匹配的版本")
    return max(candidates, key=version_key)


def fabric_installer():
    candidates = versions_of("fabric-installer")
    stable = [v for v in candidates if "-" not in v]
    return max(stable or candidates, key=version_key)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    command = sys.argv[1]
    if command == "neoforge-version" and len(sys.argv) == 3:
        print(neoforge_version(sys.argv[2]))
    elif command == "fabric-api" and len(sys.argv) == 3:
        print(fabric_api(sys.argv[2]))
    elif command == "fabric-installer":
        print(fabric_installer())
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
