#!/usr/bin/env python3
"""versions.json 的查询工具（CI 与本地共用，避免 workflow 内联脚本难以验证）。

子命令：
  matrix            输出 GitHub Actions 动态构建矩阵 JSON
  covers MC LOADER  输出该节点声明覆盖的 MC 版本（逗号分隔；未登记则回退为 MC 本身）
  javas             输出启用节点用到的 Java 版本集合（如 "21 / 25"）
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


def cmd_matrix():
    include = [
        {"node": f"{n['mc']}-{n['loader']}", "java": str(n["java"])}
        for n in enabled_nodes(load())
    ]
    print(json.dumps({"include": include}, separators=(",", ":")))


def cmd_covers(mc, loader):
    match = [n for n in load()["nodes"] if n["mc"] == mc and n["loader"] == loader]
    print(", ".join(match[0].get("covers", [mc])) if match else mc)


def cmd_javas():
    values = sorted({n["java"] for n in enabled_nodes(load())})
    print(" / ".join(str(v) for v in values))


def main():
    command, args = sys.argv[1] if len(sys.argv) > 1 else "", sys.argv[2:]
    if command == "matrix":
        cmd_matrix()
    elif command == "covers" and len(args) == 2:
        cmd_covers(args[0], args[1])
    elif command == "javas":
        cmd_javas()
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
