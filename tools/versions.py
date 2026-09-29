#!/usr/bin/env python3
"""versions.json 的查询工具（CI 与本地共用，避免 workflow 内联脚本难以验证）。

子命令：
  matrix            输出 GitHub Actions 动态构建矩阵 JSON
  range MC LOADER   输出该节点声明的 MC 覆盖范围（人类可读；未登记范围则返回 MC 本身）
  maven MC LOADER   输出 NeoForge 用的 Maven 区间语法（如 [1.21.3] 或 [1.21.3,1.21.5)）
  semver MC LOADER  输出 Fabric 用的 semver 区间语法（如 1.21.3 或 >=1.21.3 <1.21.5）
  javas             输出启用节点用到的 Java 版本集合（如 "21 / 25"）

`mcRange` 结构（写在 versions.json 的节点里，省略表示只支持该节点自身的 MC 版本）：
  "mcRange": { "from": "1.21.3", "toExclusive": "1.21.5" }

范围只允许写**经过真 jar + 真服务器验证**的版本；验证工具见 tools/jar-server-smoke.sh。
"""

import json
import sys
from pathlib import Path

VERSIONS = Path(__file__).resolve().parent.parent / "versions.json"


def load():
    with open(VERSIONS, encoding="utf-8") as fh:
        return json.load(fh)


def enabled_nodes(data):
    return [n for n in data["nodes"] if n.get("enabled")]


def node_range(node):
    """返回 (from, toExclusive)；单版本时 toExclusive 为 None。"""
    rng = node.get("mcRange") or {}
    return rng.get("from", node["mc"]), rng.get("toExclusive")


def find_node(mc, loader):
    return next((n for n in load()["nodes"] if n["mc"] == mc and n["loader"] == loader), None)


def cmd_matrix():
    include = [
        {"node": f"{n['mc']}-{n['loader']}", "java": str(n["java"])}
        for n in enabled_nodes(load())
    ]
    print(json.dumps({"include": include}, separators=(",", ":")))


def cmd_range(mc, loader):
    node = find_node(mc, loader)
    if node is None:
        print(mc)
        return
    start, end = node_range(node)
    print(start if end is None else f">={start} <{end}")


def cmd_maven(mc, loader):
    node = find_node(mc, loader)
    start, end = node_range(node) if node else (mc, None)
    print(f"[{start}]" if end is None else f"[{start},{end})")


def cmd_semver(mc, loader):
    node = find_node(mc, loader)
    start, end = node_range(node) if node else (mc, None)
    print(start if end is None else f">={start} <{end}")


def cmd_javas():
    values = sorted({n["java"] for n in enabled_nodes(load())})
    print(" / ".join(str(v) for v in values))


def main():
    command, args = sys.argv[1] if len(sys.argv) > 1 else "", sys.argv[2:]
    if command == "matrix":
        cmd_matrix()
    elif command == "javas":
        cmd_javas()
    elif command in ("range", "maven", "semver") and len(args) == 2:
        {"range": cmd_range, "maven": cmd_maven, "semver": cmd_semver}[command](args[0], args[1])
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
