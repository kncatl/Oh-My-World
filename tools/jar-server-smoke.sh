#!/usr/bin/env bash
# 用「真 jar + 真服务器」验证一个已构建的 jar 能否在目标 MC 版本上加载并生成世界。
#
# 用法: tools/jar-server-smoke.sh <jar> <目标MC> <neoforge|fabric> [等待秒数] [default|dimension|dimension-alias|marker]
# 例:   tools/jar-server-smoke.sh \
#         versions/1.21.3-neoforge/build/libs/oh-my-world-1.21.3-neoforge-1.1.6.jar 1.21.4 neoforge
#       tools/jar-server-smoke.sh \
#         versions/26.3-fabric/build/libs/oh-my-world-26.3-fabric-1.2.0.jar 26.3 fabric 45 dimension
#
# 检查模式：
#   default         —— 常规冒烟公式（主世界特征方块），见 smoke-server-config.py
#   dimension       —— 分节冒烟（1）：overworld+the_nether 公式、末地缺失=原版；
#                      启动后自动 forceload 下界/末地并做按维度核验
#   dimension-alias —— 分节冒烟（2）：无 overworld 节 + the_end 别名；同样 forceload
#   marker          —— marker 驱动冒烟：预置 world/ohmyworld_marker.txt（server_mode=false），
#                      验证"已有世界 + 分节 marker"的恢复路径；核验规则同 dimension
#
# 为什么需要它：开发服（runServer）按版本编译源码，只能证明「该版本的源码能跑」；
# 要证明「为 A 版本构建的 jar 能否在 B 版本加载」只能用真服务器 + 真 jar。
#
# 工具会把 jar 清单里的 MC 范围改写成只接受目标版本（并把 neo/fabric-api 依赖
# 对齐到服务器实际安装的版本），因此它验证的是「代码/ABI 兼容性」，与清单是否开放无关。
# 一旦通过，就把目标版本写进 versions.json 的 mcRange，让构建真正开放该范围。
#
# 服务器与日志放在 ~/omw-verify/jar-smoke/<loader>-<mc>/（可重复使用）。
set -euo pipefail

JAR="${1:?用法: tools/jar-server-smoke.sh <jar> <目标MC> <neoforge|fabric> [等待秒数] [检查模式]}"
TARGET_MC="${2:?缺少目标 MC 版本}"
LOADER="${3:?缺少加载器（neoforge|fabric）}"
SETTLE="${4:-15}"
CHECK_MODE="${5:-default}"
case "$CHECK_MODE" in
    default|dimension|dimension-alias|marker|structure-none|structure-only|biome-desert|biome-vanilla|biome-structures|features-all|features-none|carvers|carvers-off|flat-carvers|flat-carvers-off|spawn|open-ranges|water|river|m1|surface|rivernet|climate|dfnoise|overlay-marker|biome-vanilla-value|biome-at-value) ;;
    *) echo "[jar-smoke] FAIL: 未知检查模式 $CHECK_MODE（default|dimension|dimension-alias|marker|structure-none|structure-only|biome-desert|biome-vanilla|biome-structures|features-all|features-none|carvers|carvers-off|flat-carvers|flat-carvers-off|spawn|open-ranges|water|river|m1|surface|rivernet|climate|dfnoise|overlay-marker|biome-vanilla-value|biome-at-value）"; exit 1 ;;
esac
# 分节/标记冒烟要在 Done 之后通过控制台 forceload 下界/末地：至少留 45 秒收完区块；
# 雕刻器冒烟（含超平坦）要 forceload 一大片区域，留 90 秒。
if [[ "$CHECK_MODE" == carvers* || "$CHECK_MODE" == flat-carvers* ]] && [[ "$SETTLE" -lt 90 ]]; then SETTLE=90;
elif [[ "$CHECK_MODE" != "default" && "$SETTLE" -lt 45 ]]; then SETTLE=45; fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="$ROOT/tools"
WORK="$HOME/omw-verify/jar-smoke/$LOADER-$TARGET_MC"
SRV="$WORK/server"
LOG="$WORK/server.log"

# JDK：1.21.x 用 21，26.x 用 25（与 versions.json 的约定一致）
JDK21="${JAVA21:-$HOME/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2}"
JDK25="${JAVA25:-$HOME/.gradle/jdks/eclipse_adoptium-25-amd64-linux.2}"
if [[ "$TARGET_MC" == 26.* ]]; then JAVA_BIN="$JDK25/bin/java"; else JAVA_BIN="$JDK21/bin/java"; fi
[[ -x "$JAVA_BIN" ]] || { echo "[jar-smoke] FAIL: 找不到 JDK: $JAVA_BIN"; exit 1; }

JAR_ABS="$(cd "$(dirname "$JAR")" && pwd)/$(basename "$JAR")"
[[ -f "$JAR_ABS" ]] || { echo "[jar-smoke] FAIL: jar 不存在: $JAR_ABS"; exit 1; }

echo "[jar-smoke] jar=$JAR_ABS"
echo "[jar-smoke] 目标: MC $TARGET_MC / $LOADER    工作目录: $WORK ($JAVA_BIN)"

mkdir -p "$SRV/mods"

# ---------- 1. 安装目标版本的服务器 ----------
if [[ "$LOADER" == "neoforge" ]]; then
    NEO_VER="${NEOFORGE_VERSION:-$(python3 "$TOOLS/resolve-loader.py" neoforge-version "$TARGET_MC")}"
    echo "[jar-smoke] NeoForge $NEO_VER"
    ARGS_FILE="$SRV/libraries/net/neoforged/neoforge/$NEO_VER/unix_args.txt"
    if [[ ! -f "$ARGS_FILE" ]]; then
        INSTALLER="$WORK/neoforge-$NEO_VER-installer.jar"
        [[ -f "$INSTALLER" ]] || curl -fsSL --retry 3 --retry-delay 5 --max-time 900 -o "$INSTALLER" \
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/$NEO_VER/neoforge-$NEO_VER-installer.jar"
        echo "[jar-smoke] 安装 NeoForge 服务端…"
        # 安装器要下一大堆库，代理偶发断链：重试几次（交接文档记录的已知问题）
        install_ok=0
        for attempt in 1 2 3; do
            if ( cd "$SRV" && "$JAVA_BIN" -jar "$INSTALLER" --installServer > "$WORK/install.log" 2>&1 ); then
                install_ok=1
                break
            fi
            echo "[jar-smoke] 安装失败（第 $attempt 次），5 秒后重试…"
            sleep 5
        done
        [[ "$install_ok" == 1 ]] || { echo "[jar-smoke] FAIL: 服务端安装失败（已重试 3 次）"; tail -30 "$WORK/install.log"; exit 1; }
    fi
    [[ -f "$SRV/user_jvm_args.txt" ]] || : > "$SRV/user_jvm_args.txt"

elif [[ "$LOADER" == "fabric" ]]; then
    FABRIC_API_VER="${FABRIC_API_VERSION:-$(python3 "$TOOLS/resolve-loader.py" fabric-api "$TARGET_MC")}"
    FABRIC_INSTALLER_VER="${FABRIC_INSTALLER_VERSION:-$(python3 "$TOOLS/resolve-loader.py" fabric-installer)}"
    echo "[jar-smoke] Fabric API $FABRIC_API_VER（installer $FABRIC_INSTALLER_VER）"
    if [[ ! -f "$SRV/fabric-server-launch.jar" ]]; then
        INSTALLER="$WORK/fabric-installer-$FABRIC_INSTALLER_VER.jar"
        [[ -f "$INSTALLER" ]] || curl -fsSL --retry 3 --retry-delay 5 --max-time 900 -o "$INSTALLER" \
            "https://maven.fabricmc.net/net/fabricmc/fabric-installer/$FABRIC_INSTALLER_VER/fabric-installer-$FABRIC_INSTALLER_VER.jar"
        echo "[jar-smoke] 安装 Fabric 服务端…"
        install_ok=0
        for attempt in 1 2 3; do
            if ( cd "$SRV" && "$JAVA_BIN" -jar "$INSTALLER" server -dir "$SRV" -mcversion "$TARGET_MC" -downloadMinecraft > "$WORK/install.log" 2>&1 ); then
                install_ok=1
                break
            fi
            echo "[jar-smoke] 安装失败（第 $attempt 次），5 秒后重试…"
            sleep 5
        done
        [[ "$install_ok" == 1 ]] || { echo "[jar-smoke] FAIL: 服务端安装失败（已重试 3 次）"; tail -30 "$WORK/install.log"; exit 1; }
    fi
    API_JAR="$SRV/mods/fabric-api-$FABRIC_API_VER.jar"
    [[ -f "$API_JAR" ]] || curl -fsSL --retry 3 --retry-delay 5 --max-time 900 -o "$API_JAR" \
        "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/$FABRIC_API_VER/fabric-api-$FABRIC_API_VER.jar"

else
    echo "[jar-smoke] FAIL: 未知加载器 $LOADER（应为 neoforge|fabric）"
    exit 1
fi

# ---------- 2. 改写 jar 清单，装入 mods/ ----------
rm -f "$SRV/mods/ohmyworld-"*.jar
PATCHED="$SRV/mods/ohmyworld-under-test.jar"
python3 - "$JAR_ABS" "$PATCHED" "$LOADER" "$TARGET_MC" "${NEO_VER:-}" "${FABRIC_API_VER:-}" <<'PY'
import json, re, sys, zipfile

src, dst, loader, mc, neo_ver, fabric_api = sys.argv[1:7]
with zipfile.ZipFile(src) as zin:
    items = {name: zin.read(name) for name in zin.namelist()}

if loader == "neoforge":
    key = "META-INF/neoforge.mods.toml"
    text = items[key].decode("utf-8")
    blocks = re.split(r"(?=\[\[dependencies)", text)
    for i, block in enumerate(blocks):
        if 'modId="minecraft"' in block:
            blocks[i] = re.sub(r'versionRange="[^"]*"', f'versionRange="[{mc}]"', block)
        elif 'modId="neoforge"' in block and neo_ver:
            blocks[i] = re.sub(r'versionRange="[^"]*"', f'versionRange="[{neo_ver},)"', block)
    items[key] = "".join(blocks).encode("utf-8")
else:
    key = "fabric.mod.json"
    meta = json.loads(items[key])
    meta["depends"]["minecraft"] = mc
    if fabric_api:
        meta["depends"]["fabric-api"] = ">=" + fabric_api
    items[key] = json.dumps(meta, ensure_ascii=False, indent=2).encode("utf-8")

with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
    for name, data in items.items():
        zout.writestr(name, data)
print(f"[jar-smoke] 已改写清单 {key}: minecraft=[{mc}]")
PY

# ---------- 3. 冒烟配置 ----------
# level-type=flat 必须显式设置：否则 Mixin 不触发、公式不会跑，验证会假阳性。
# 端口交给系统分配（并行验证不会撞端口）。
PORT=auto
case "$CHECK_MODE" in
    dimension)       SMOKE_CONFIG_MODE="--dimension-formula" ;;
    dimension-alias) SMOKE_CONFIG_MODE="--dimension-alias" ;;
    marker)          SMOKE_CONFIG_MODE="--marker-formula" ;;
    structure-none)  SMOKE_CONFIG_MODE="--structure-none" ;;
    structure-only)  SMOKE_CONFIG_MODE="--structure-only" ;;
    biome-desert)    SMOKE_CONFIG_MODE="--biome-desert" ;;
    biome-vanilla)   SMOKE_CONFIG_MODE="--biome-vanilla" ;;
    biome-vanilla-value) SMOKE_CONFIG_MODE="--biome-vanilla-value" ;;
    biome-at-value)  SMOKE_CONFIG_MODE="--biome-at-value" ;;
    biome-structures) SMOKE_CONFIG_MODE="--biome-structures" ;;
    features-all)    SMOKE_CONFIG_MODE="--features-all" ;;
    features-none)   SMOKE_CONFIG_MODE="--features-none" ;;
    carvers)         SMOKE_CONFIG_MODE="--carvers" ;;
    carvers-off)     SMOKE_CONFIG_MODE="--carvers-off" ;;
    flat-carvers)    SMOKE_CONFIG_MODE="--flat-carvers" ;;
    flat-carvers-off) SMOKE_CONFIG_MODE="--flat-carvers-off" ;;
    spawn)           SMOKE_CONFIG_MODE="--spawn-formula" ;;
    open-ranges)     SMOKE_CONFIG_MODE="--open-ranges" ;;
    water)           SMOKE_CONFIG_MODE="--water" ;;
    river)           SMOKE_CONFIG_MODE="--river" ;;
    m1)              SMOKE_CONFIG_MODE="--m1-functions" ;;
    surface)         SMOKE_CONFIG_MODE="--surface" ;;
    rivernet)        SMOKE_CONFIG_MODE="--rivernet" ;;
    climate)         SMOKE_CONFIG_MODE="--climate" ;;
    overlay-marker)  SMOKE_CONFIG_MODE="--overlay-marker" ;;
    dfnoise)         SMOKE_CONFIG_MODE="--dfnoise" ;;
    *)               SMOKE_CONFIG_MODE="--force-formula" ;;
esac
# 先清世界再写配置：marker 模式会在配置阶段预置 world/ohmyworld_marker.txt
rm -rf "$SRV/world" "$SRV/logs"
python3 "$TOOLS/smoke-server-config.py" "$SRV" "$PORT" $SMOKE_CONFIG_MODE

# ---------- 4. 启动、等待、停止 ----------
: > "$LOG"

# 分节冒烟：服务器就绪后通过控制台 forceload 下界/末地（各生成出生点区块）。
# 用管道喂 stdin；$! 是管道最后一段（即 exec 出的服务端 JVM），kill 目标不变。
console_commands() {
    while ! grep -q 'Done (' "$LOG" 2>/dev/null; do sleep 1; done
    sleep 3
    if [[ "$CHECK_MODE" == structure-* || "$CHECK_MODE" == biome-* ]]; then
        # 结构/群系冒烟：forceload 单次上限 256 区块；在出生区之外再补 16×16 区块
        # （出生区本身约 29×29 区块，矿井密度高，样本量足够）
        echo "execute in minecraft:overworld run forceload add 512 0 767 255"
        if [[ "$CHECK_MODE" == "biome-vanilla" ]]; then
            # 原版群系分布要跨气候带采样：再补三个远离出生点的区域（种子固定，样本可复现）
            sleep 12
            echo "execute in minecraft:overworld run forceload add 6656 6656 6911 6911"
            sleep 12
            echo "execute in minecraft:overworld run forceload add -7168 -7168 -6913 -6913"
            sleep 12
            echo "execute in minecraft:overworld run forceload add 512 -7168 767 -6913"
            sleep 12
        elif [[ "$CHECK_MODE" == "biome-vanilla-value" || "$CHECK_MODE" == "biome-at-value" ]]; then
            # M3.3：下界 multinoise 参数表查询——加载下界一小片即可
            echo "execute in minecraft:the_nether run forceload add -192 -192 63 63"
            sleep 20
        else
            sleep 20
        fi
    elif [[ "$CHECK_MODE" == features-* ]]; then
        # 特性冒烟：下界玄武岩三角洲的装饰方块样本；16×16 区块足以扫到斑块/荧石
        echo "execute in minecraft:the_nether run forceload add 0 0 255 255"
        sleep 20
    elif [[ "$CHECK_MODE" == carvers* || "$CHECK_MODE" == flat-carvers* ]]; then
        # 雕刻器冒烟：forceload 256 区块再收 60 秒——隧道/峡谷稀疏，样本要大
        echo "execute in minecraft:overworld run forceload add 1024 0 1279 255"
    elif [[ "$CHECK_MODE" == "rivernet" ]]; then
        # 河网冒烟：26.x 启动只完整生成出生区块半径 2；forceload 256 区块覆盖原点
        # 西南方向的河段（径向坡保证原点附近必有河），再收 75 秒生成
        echo "execute in minecraft:overworld run forceload add -192 -192 63 63"
        sleep 75
    elif [[ "$CHECK_MODE" == "climate" ]]; then
        # 共享气候冒烟：forceload 256 区块扩大温度分带采样范围（同 rivernet 的理由）
        echo "execute in minecraft:overworld run forceload add -192 -192 63 63"
        sleep 75
    elif [[ "$CHECK_MODE" == "dfnoise" ]]; then
        # 原版数据冒烟：forceload 256 区块扩大比较场的采样范围（同 climate）
        echo "execute in minecraft:overworld run forceload add -192 -192 63 63"
        sleep 75
    elif [[ "$CHECK_MODE" == "spawn" ]]; then
        # 出生点冒烟：forceload 覆盖原点与出生点周边（16×16=256，上限内），
        # 便于用探针方块读出"各区块生成时读到的出生点"
        echo "execute in minecraft:overworld run forceload add -128 -128 127 127"
        sleep 60
    else
        echo "execute in minecraft:the_nether run forceload add 0 0"
        sleep 15
        echo "execute in minecraft:the_end run forceload add 0 0"
        sleep 15
    fi
}

if [[ "$LOADER" == "neoforge" ]]; then
    if [[ "$CHECK_MODE" != "default" ]]; then
        console_commands | ( cd "$SRV" && exec "$JAVA_BIN" -Xmx2G @user_jvm_args.txt @"libraries/net/neoforged/neoforge/$NEO_VER/unix_args.txt" nogui ) >>"$LOG" 2>&1 &
    else
        ( cd "$SRV" && exec "$JAVA_BIN" -Xmx2G @user_jvm_args.txt @"libraries/net/neoforged/neoforge/$NEO_VER/unix_args.txt" nogui ) >>"$LOG" 2>&1 &
    fi
else
    if [[ "$CHECK_MODE" != "default" ]]; then
        console_commands | ( cd "$SRV" && exec "$JAVA_BIN" -Xmx2G -jar fabric-server-launch.jar nogui ) >>"$LOG" 2>&1 &
    else
        ( cd "$SRV" && exec "$JAVA_BIN" -Xmx2G -jar fabric-server-launch.jar nogui ) >>"$LOG" 2>&1 &
    fi
fi
PID=$!

started=0
for _ in $(seq 1 180); do
    if grep -q 'Done (' "$LOG"; then started=1; break; fi
    # 明确的兼容性断裂：不必等满超时。
    # 判定要严格 —— 加载器自身会打无害的 ClassNotFoundException 警告（如 log4j
    # context selector），把那些当成失败会误报；只有错误确实来自我们的模组才算。
    if grep -q 'formula chunk fill failed' "$LOG"; then
        break
    fi
    if grep -A3 -m1 -E 'NoSuchMethodError|NoClassDefFoundError' "$LOG" | grep -q 'ohmyworld'; then
        break
    fi
    if ! kill -0 "$PID" 2>/dev/null; then break; fi
    sleep 2
done
[[ "$started" == 1 ]] && sleep "$SETTLE"

kill -TERM "$PID" 2>/dev/null || true
for _ in $(seq 1 30); do kill -0 "$PID" 2>/dev/null || break; sleep 2; done
kill -KILL "$PID" 2>/dev/null || true
wait "$PID" 2>/dev/null || true

# ---------- 5. 判定 ----------
fail() {
    echo "[jar-smoke] FAIL: $1"
    echo "----- 日志尾部 -----"
    tail -50 "$LOG"
    exit 1
}

if [[ "$started" != 1 ]]; then
    hint="$(grep -B1 -A3 -m1 -E 'NoSuchMethodError|NoClassDefFoundError' "$LOG" | grep -m1 'ohmyworld' || true)"
    if [ -z "$hint" ]; then
        hint="$(grep -m1 'formula chunk fill failed' "$LOG" || true)"
    fi
    [ -n "$hint" ] && echo "[jar-smoke] 根因提示: $hint"
    fail "服务器未完成启动（没有 Done）"
fi
# 模组初始化的可靠证据：启动时会往游戏目录写指南与校验标记
[[ -f "$SRV/ohmyworld/.guide_zh_cn.sha256" ]] \
    || fail "模组未初始化（缺少 ohmyworld/ 指南标记）—— 很可能被加载器拒绝或初始化失败"
grep -qE 'formula (chunk fill|base-height|base-column) failed' "$LOG" && fail "公式报错（图案已被禁用）"
grep -qE 'Mixin apply|Exception in thread|Crash report' "$LOG" && fail "日志中出现 Mixin/异常/崩溃"

# 26.x 起区块文件挪到 world/dimensions/<ns>/<dim>/region/（1.21.x 的布局是
# world/region、world/DIM1/region 等）；统一在 world/ 下按 */region/*.mca 找，兼容两代。
# 命令替换里必须给出 || echo 0 —— set -e + pipefail 会让 find 失败直接终止脚本，
# 从而跳过下面的 fail 诊断。
CHUNKS=$(find "$SRV/world" -path '*/region/*.mca' 2>/dev/null | wc -l | tr -d ' ' || echo 0)
[[ "$CHUNKS" -gt 0 ]] || fail "没有生成 region 文件"
# 世界里必须能找到冒烟公式的特征方块：证明「Mixin → fillChunk → 公式 → 注册表查方块」
# 整条链路真的跑过（否则就是"服务器起来了但公式没生效"的假阳性）。
case "$CHECK_MODE" in
    dimension|marker)
        python3 "$TOOLS/smoke-check-world.py" --dimension-smoke 1 "$SRV" \
            || fail "分节冒烟（1/marker）核验失败：逐维度检查未通过"
        ;;
    dimension-alias)
        python3 "$TOOLS/smoke-check-world.py" --dimension-smoke 2 "$SRV" \
            || fail "分节冒烟（2）核验失败：逐维度检查未通过"
        ;;
    structure-none)
        python3 "$TOOLS/smoke-check-world.py" --structure-smoke 1 "$SRV" \
            || fail "结构冒烟（none）核验失败：下界出现/缺少了预期之外的结构"
        ;;
    structure-only)
        python3 "$TOOLS/smoke-check-world.py" --structure-smoke 2 "$SRV" \
            || fail "结构冒烟（only）核验失败：白名单外的结构出现或化石缺失"
        ;;
    biome-desert)
        python3 "$TOOLS/smoke-check-world.py" --biome-smoke 1 "$SRV" \
            || fail "群系冒烟（desert）核验失败：主世界群系不是纯沙漠"
        ;;
    biome-vanilla)
        python3 "$TOOLS/smoke-check-world.py" --biome-smoke 2 "$SRV" \
            || fail "群系冒烟（vanilla）核验失败：群系多样性不足"
        ;;
    biome-vanilla-value)
        if grep -q "formula biome uncovered" "$LOG"; then
            fail "biome 行 vanilla 群系值解析失败（日志出现 formula biome uncovered）"
        fi
        python3 "$TOOLS/smoke-check-world.py" --biome-smoke 9 "$SRV" \
            || fail "biome 行 vanilla 群系值核验失败：下界没有出现原版群系"
        ;;
    biome-at-value)
        if grep -q "formula biome uncovered" "$LOG"; then
            fail "biome_at 参数表查询解析失败（日志出现 formula biome uncovered）"
        fi
        python3 "$TOOLS/smoke-check-world.py" --biome-smoke 9 "$SRV" \
            || fail "biome_at 参数表查询核验失败：下界没有出现原版群系"
        ;;
    biome-structures)
        python3 "$TOOLS/smoke-check-world.py" --structure-smoke 3 "$SRV" \
            || fail "群系结构冒烟核验失败：换群系后沙漠神殿没有解锁"
        ;;
    features-all)
        python3 "$TOOLS/smoke-check-world.py" --features-smoke 1 "$SRV" \
            || fail "特性冒烟（all）核验失败：玄武岩三角洲装饰没有出现"
        ;;
    features-none)
        python3 "$TOOLS/smoke-check-world.py" --features-smoke 2 "$SRV" \
            || fail "特性冒烟（none）核验失败：仍出现装饰方块"
        ;;
    carvers|carvers-off|flat-carvers|flat-carvers-off)
        python3 "$TOOLS/smoke-check-world.py" --biome-smoke 8 "$SRV" \
            || fail "雕刻器冒烟核验失败：石块/世界不符合预期"
        ;;
    spawn)
        python3 "$TOOLS/smoke-check-world.py" --require-all "$SRV" \
            || fail "出生点冒烟核验失败：公式特征方块缺失"
        ;;
    surface)
        python3 "$TOOLS/smoke-check-world.py" \
            --expect overworld minecraft:sea_lantern,minecraft:polished_blackstone_bricks,minecraft:ochre_froglight,minecraft:glass \
            "$SRV" || fail "表面通道冒烟核验失败：sd/wd/slope 特征方块缺失"
        ;;
    rivernet)
        python3 "$TOOLS/smoke-check-world.py" \
            --expect overworld minecraft:sand,minecraft:water \
            "$SRV" || fail "河网冒烟核验失败：河道沙/水体缺失"
        ;;
    climate)
        python3 "$TOOLS/smoke-check-world.py" \
            --expect overworld minecraft:sand,minecraft:polished_blackstone_bricks \
            "$SRV" || fail "共享气候冒烟核验失败：温度分带特征方块缺失"
        ;;
    overlay-marker)
        python3 "$TOOLS/smoke-check-world.py" \
            --expect overworld minecraft:sea_lantern,minecraft:white_concrete,minecraft:bricks,minecraft:gray_concrete,minecraft:glass,minecraft:terracotta,minecraft:stone \
            "$SRV" || fail "叠加模式冒烟核验失败：特征方块缺失或地形被当成 flat 公式"
        ;;
    dfnoise)
        python3 "$TOOLS/smoke-check-world.py" \
            --expect overworld minecraft:sand,minecraft:bricks,minecraft:gravel,minecraft:clay,minecraft:white_concrete,minecraft:gray_concrete \
            "$SRV" || fail "原版数据冒烟核验失败：df/noise/vheight 特征方块缺失"
        ;;
    open-ranges|water|river|m1)
        python3 "$TOOLS/smoke-check-world.py" --require-all "$SRV" \
            || fail "开区间/水域/河流/新函数冒烟核验失败：公式特征方块缺失"
        ;;
    *)
        python3 "$TOOLS/smoke-check-world.py" "$SRV" || fail "世界里没有公式特征方块（公式没有生效）"
        ;;
esac

echo "[jar-smoke] OK: jar 在 MC $TARGET_MC / $LOADER 上加载并生成成功（region 文件 $CHUNKS 个）"
grep -E 'Done \(' "$LOG" | tail -1
echo "[jar-smoke] 若要把 $TARGET_MC 纳入覆盖范围，请把 versions.json 对应节点的 mcRange 扩展后再跑一次（构建产物会真正声明该范围）。"
