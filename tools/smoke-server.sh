#!/usr/bin/env bash
# 启动某个节点的开发服务端并生成出生点，做最小冒烟检查：
#   1) 服务端能在限定时间内完成启动（出现 "Done ("）；
#   2) 没有公式求值/填充报错、没有异常或崩溃；
#   3) 世界目录里生成了 region 文件。
#
# 用法: tools/smoke-server.sh <节点> [启动后额外等待秒数]
# 例:   tools/smoke-server.sh 1.21.1-neoforge 20
#
# 这是本地验证工具（CI 里跑整套 20+ 节点过重），配合 ~/omw-verify/run-e2e.sh
# 的世界比对一起用：冒烟看"能不能正常跑"，比对看"生成结果对不对"。
set -euo pipefail

NODE="${1:?用法: tools/smoke-server.sh <节点> [等待秒数]}"
SETTLE="${2:-20}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

RUN_DIR="versions/$NODE/run"
LOG="$(mktemp -t "ohmyworld-smoke-${NODE}.XXXXXX.log")"
echo "[smoke] 节点=$NODE  日志=$LOG"

# 统一配置：level-type=flat（必须，否则 Mixin 不触发、公式不会跑）、固定种子、
# 关结构、端口交给系统分配（并行验证不会撞端口）。
# 冒烟公式刻意使用白名单之外的方块（触发 RegistryLookup）并用特征方块核验世界。
mkdir -p "$RUN_DIR"
PORT=auto
CONFIG_OUT="$(python3 "$ROOT/tools/smoke-server-config.py" "$RUN_DIR" "$PORT")"
echo "$CONFIG_OUT" | grep -v '^SMOKE_FORMULA=' || true
SMOKE_FORMULA="$(echo "$CONFIG_OUT" | sed -n 's/^SMOKE_FORMULA=//p')"

rm -rf "$RUN_DIR/world"

./gradlew "$NODE:runServer" --console=plain > "$LOG" 2>&1 &
GRADLE_PID=$!

started=0
for _ in $(seq 1 300); do
    if grep -q 'Done (' "$LOG"; then started=1; break; fi
    if ! kill -0 "$GRADLE_PID" 2>/dev/null; then break; fi
    sleep 2
done

if [ "$started" = 1 ]; then
    sleep "$SETTLE"
fi

# 停止服务端：NeoForge 的 launch target 藏在 @serverRunVmArgs.txt 里（而且
# cmdline 超过 pkill 的 4096 字节匹配窗口），Fabric 侧则是 devlaunchinjector。
pkill -TERM -f 'serverRunVmArgs' 2>/dev/null || true
pkill -TERM -f 'devlaunchinjector' 2>/dev/null || true
sleep 5
pkill -KILL -f 'serverRunVmArgs' 2>/dev/null || true
pkill -KILL -f 'devlaunchinjector' 2>/dev/null || true
wait "$GRADLE_PID" 2>/dev/null || true

fail() {
    echo "[smoke] FAIL: $1"
    echo "----- 日志尾部 -----"
    tail -40 "$LOG"
    exit 1
}

[ "$started" = 1 ] || fail "服务端未在限定时间内完成启动（没有出现 Done）"
if grep -qE 'formula (chunk fill|base-height|base-column) failed' "$LOG"; then
    fail "公式求值/填充报错（图案已被禁用）"
fi
if grep -qE 'Exception in thread|Crash report|Failed to start|Mixin apply' "$LOG"; then
    fail "日志中出现异常、崩溃或 Mixin 应用失败"
fi

CHUNKS=$(find "$RUN_DIR/world/region" -name '*.mca' 2>/dev/null | wc -l | tr -d ' ')
[ "$CHUNKS" -gt 0 ] || fail "没有生成任何 region 文件"

# 若当前用的是我们写入的冒烟公式，则必须能在世界里找到特征方块——
# 这证明「Mixin → fillChunk → 公式 → 注册表查方块」整条链路真的跑过。
if [ "$SMOKE_FORMULA" = "1" ]; then
    python3 "$ROOT/tools/smoke-check-world.py" "$RUN_DIR" || fail "世界里没有公式特征方块（公式没有生效）"
fi

echo "[smoke] OK: $NODE 出生点生成成功（region 文件 $CHUNKS 个）"
grep -E 'Done \(' "$LOG" | tail -1
