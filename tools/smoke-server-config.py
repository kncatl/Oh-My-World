#!/usr/bin/env python3
"""为冒烟测试写服务端配置（开发服与真 jar 验证共用）。

用法: smoke-server-config.py <服务端目录> <端口> [--force-formula] [--seed-formula]
                            [--dimension-formula | --dimension-alias]

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

--spawn-formula：改用"出生点专项"冒烟公式：以 (spawnx, spawnz) 为圆心、半径 12
  的圆盘铺海晶灯，其余铺黑石砖。出生点读错（例如恒为 0）时灯盘不会落在
  level.dat 的 SpawnX/SpawnZ 上——配合 smoke-check-world.py --require-all，
  再用 dump-biomes.py spawn 与块扫描复核灯盘圆心。

--dimension-formula：分节冒烟（1）：{overworld=...}{the_nether=...}——末地未写
  按原版；配合控制台 forceload 生成下界/末地区块后用
  smoke-check-world.py --dimension-smoke 1 判定。
--dimension-alias：分节冒烟（2）：{the_nether=...}{the_end=the_nether}——没有
  overworld 节（主世界=超平坦基座、无公式）、末地走别名。配合
  smoke-check-world.py --dimension-smoke 2 判定。
--marker-formula：marker 驱动冒烟：server_mode=false，预先在 world 目录写入
  与模式（1）相同的分节 marker——验证「已有世界 + 分节 marker」的恢复路径
  （server_mode 冒烟不经过它）。核验规则同 --dimension-smoke 1。

--biome-desert / --biome-vanilla（P4.2）：主世界 [biome:...] 群系冒烟；配合
  smoke-check-world.py --biome-smoke 1|2 判定（区块 NBT 群系调色板扫描）。
--biome-formula / --biome-formula-fallback（公式群系）：主世界 biome 行冒烟；
  配合 smoke-check-world.py --biome-smoke 3|4 判定。
  3 = 两段全覆盖（y=-64..30 深暗之域 / y=31..319 沙漠，fallback 缺省 none）；
  4 = [biome-fallback:3d]：低段深暗之域 + 其余按实际 y 交还原版分布。
--biome-terrain：地形联动冒烟（biome 行调用 terrain/surfis/blockis）；
  配合 smoke-check-world.py --biome-smoke 5 判定（三类群系都出现才算通过）。
--biome-biomeis：[biome:vanilla] + 方块层 biomeis 冒烟（末位为**群系表达式**，
  y>0 ? ocean : desert，同时覆盖命中与不命中）；配合
  smoke-check-world.py --biome-smoke 6 判定（两种"按群系铺的方块"都要出现）。
--natural：自然世界冒烟（1.2.5：noise/fbm + y 变量 + 洞穴）；配合
  smoke-check-world.py --biome-smoke 7 判定（地表材质方块都要出现）。
--carvers / --carvers-off：雕刻器 A/B 冒烟（**普通世界**类型；超平坦世界的雕刻器
  由 1.2.6 起通过"干燥代理"支持，见下一条）；公式为整块石头 + [carvers:vanilla|none]；
  配合 --biome-smoke 8，再用 dump-biomes.py stats 对比 y 段空气率判定是否真的被掏空。
--flat-carvers / --flat-carvers-off：超平坦雕刻器 A/B 冒烟（**我们自己的 flat_plus 预设**）；
  公式同上（整块石头到 y=80 + [carvers:vanilla|none]），用于验证 1.2.6 的
  "超平坦世界跑原版雕刻器"（干燥代理）确实在公式地形上掏出了洞穴/峡谷：
  carvers-on 的空气率应显著高于 carvers-off（后者≈0）。配合 --biome-smoke 8。
--features-all / --features-none（P4.3）：下界固定玄武岩三角洲 + [features:...]
  装饰开关冒烟；配合 smoke-check-world.py --features-smoke 1|2 判定（装饰方块扫描）。

--open-ranges：开区间层语法冒烟（1.2.6）：y=..（全高，ly 从维度最低 y 起算）、
  y=60..（从 60 到维度顶部）两种写法 + 一段普通范围；底部三层铺海晶灯用于
  核对 ly 基准，三段（灯 / 黑石砖 / 圆石）互不重叠。配合 --require-all；
  再用逐方块扫描核对三条边界。

--water：水域配方冒烟（1.2.6：spline + waterline 一起）：样条地形高度 + 水位面，
  地表铺海晶灯、内部黑石砖、水位以下灌水。配合 --require-all；
  再用 stats 核对水域存在且没有"高海拔水体"。

--river：河流配方冒烟（原版式河谷）：基础地形 fbm + 河道线（noise2 零等值线，
  对应原版的"谷地切片"）+ 河床插值 + 水位以下淹没。带宽 ≈ 3×阈值×尺度；
  配合 --require-all（河床沙/岩石两块都在）；水体宽度再用逐列扫描复核。

--m1-functions（1.3.0）：新函数冒烟——元组 let（warp2）+ 噪声变体（fbm2 重载外的
  ridged2/fbm2e）+ 循环（sum）+ 空间助手（terrace/slope/isodist/clamp）+ cache2d/3d
  + seedhash 逐块噪声决定海晶灯 / 黑石砖；两种特征方块都要出现（--require-all），
  证明「解析 → 编译 → 求值」整条链路在新语法下真的跑过。

--surface（1.3.1）：表面通道冒烟——台阶地形（60/64 交替，每 8 格一级）+ 低地灌水，
  surface 行按 sd/wd/slope 分层铺 海晶灯 / 黑石砖 / 蛙明灯 / 玻璃，其余 keep。
  配合 smoke-check-world.py 的显式 --expect（四种特征方块都要出现）。

--rivernet（1.3.1）：河网冒烟——rivernet(192, 7) 刻河道（河床沙、水体），
  基础地形 fbm；配合显式 --expect（沙与水都要出现，证明河网真的接入生成）。

--climate（1.3.2）：共享气候冒烟——用 climate(temperature, x, z) > climate(temperature, z, x)
  的反对称比较铺沙 / 黑石砖；forceload 扩大采样范围；两种特征方块都要出现（--expect），
  证明气候视图构建、量化取值与公式取数整条链路真的跑过。

--overlay-empty（1.3.2 M3.5 验收）：空叠加恒等式 `y=..: vanilla`——断言首区块
  写入数为 0（等价于与原版逐方块一致；需 SMOKE_DEBUG=1）+ 原版地形生成成功。

--overlay-marker（1.3.2 M3.5）：叠加模式冒烟——普通噪声世界（server_mode）；
  叠加层在深层石层里铺 海晶灯/白混凝土 两种特征方块（keep 与 vanilla 还原各一半），
  地表与群系保持原版（stone 仍在 = 没有被当成 flat 公式填平）。

--dfnoise（1.3.2）：原版数据冒烟——df(minecraft:overworld/ridges, …) 与
  noise(minecraft:temperature, …) 各做一次反对称比较、vheight(x, z) 做一次
  x/z 互换比较（沙/砖、砾/黏土、白/灰混凝土六种特征方块，三个 y 特征带各一组）；
  配合显式 --expect，证明注册表密度函数/噪声/地表估计的构建、接线与求值整条链路
  真的跑过（df/noise 用 cache2d 摊薄；vheight 限定 8×8 格点以控制 forceload 成本）。

输出最后一行：SMOKE_FORMULA=1 表示当前配置就是我们写入的冒烟公式（应做特征方块核验）；
SMOKE_FORMULA=0 表示保留了已有公式（不要按特征方块判定）。
"""

import sys
import os
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

# 出生点专项冒烟：以出生点（出生区块中心）为圆心、半径 12 的圆盘铺海晶灯，
# 其余铺黑石砖。出生点读错（如恒为 0）→ 灯盘不在预期中心；
# 配合 --require-all（两块都要出现）+ dump-biomes.py spawn 复核圆心
# （预期中心 = 出生区块中心 = 存档出生点所在区块的中点）。
SPAWN_FORMULA = (
    "y=-64: minecraft:bedrock;"
    "y=-63..64: ((x - spawnx) * (x - spawnx) + (z - spawnz) * (z - spawnz) <= 144)"
    " ? minecraft:sea_lantern : minecraft:polished_blackstone_bricks"
)

# 开区间层语法冒烟（1.2.6）：
#  · y=..:          全高（ly 从维度最低 y 起算）→ 底部三层（ly<3）铺海晶灯；
#  · y=-61..59:     普通范围 → 黑石砖；
#  · y=60..:        从 60 到维度顶部（开终点）→ 圆石。
# 三段互不重叠（后写覆盖先写，重叠会把前面的结果盖掉）。
OPEN_RANGES_FORMULA = (
    "{overworld=y=..: ly < 3 ? minecraft:sea_lantern : minecraft:air;"
    " y=-61..59: minecraft:polished_blackstone_bricks;"
    " y=60..: minecraft:stone}"
)

# 水域配方冒烟（1.2.6：spline + waterline）：
# 样条地形高度 h（噪声经 spline 映射）+ 水位 w（waterline）；
# 地形低于水位处自动灌水（地表海晶灯、内部黑石砖），水面之上为空气。
WATER_FORMULA = (
    "{overworld="
    "let n = noise2(x, z, 1400, 1);"
    "let h = 64 + spline(n, -0.6, -26, -0.15, -4, 0.15, 6, 0.6, 30);"
    "let w = waterline(x, z, 63);"
    "y=..: y <= h"
    " ? (y > h - 4 ? minecraft:sea_lantern : minecraft:polished_blackstone_bricks)"
    " : (y <= w ? minecraft:water : minecraft:air)}"
)

# 河流配方冒烟（原版式河谷：噪声零等值线 + 地形插值 + 水位淹没）：
#  · 基础地形 base（fbm）；
#  · 河道线 = noise2 的零等值线（对应原版 weirdness∈[-0.05,0.05] 的谷地切片）；
#  · 河谷强度 t：中心 1、带宽外侧 0（带宽 ≈ 3×阈值×尺度）；
#  · 河床压到 57（水面 62 以下），低地/海同样会被淹没（水系连通）。
# 判定：海晶灯（河床沙）+ 黑石砖（岩石）都在 → 公式跑通；水体宽度另用 stats/扫描。
RIVER_FORMULA = (
    "{overworld="
    "let base = 63 + fbm2(x, z, 900, 4, 1) * 26;"
    "let w = noise2(x, z, 700, 7);"
    "let t = 1 - smoothstep(clamp(abs(w) / 0.012, 0, 1));"
    "let h = lerp(base, 57, t * t);"
    "y=..: y <= h"
    " ? (y > h - 3 ? minecraft:sea_lantern : (y > h - 8 ? minecraft:polished_blackstone_bricks"
    " : minecraft:stone))"
    " : (y <= 62 ? minecraft:water : minecraft:air)}"
)

# 分节冒烟（1）：主世界 + 下界各写公式，末地不写（必须保持原版）。
# 下界用 ochre_froglight（非自然生成、非白名单，同样走注册表查询）。
DIMENSION_FORMULA = (
    "{overworld="
    "y=-64: minecraft:bedrock;"
    "y=-63..64: (x+z)%2==0 ? minecraft:sea_lantern : minecraft:polished_blackstone_bricks"
    "}"
    "{the_nether="
    "y=0..40: minecraft:ochre_froglight"
    "}"
)

# 分节冒烟（2）：没有 overworld 节 + 末地走别名（= 下界公式）。
DIMENSION_ALIAS_FORMULA = (
    "{the_nether="
    "y=0..40: minecraft:ochre_froglight"
    "}"
    "{the_end=the_nether}"
)

# P4 结构冒烟：主世界公式带 [structure:...] 指令（配合 16×16 区块 forceload 网格与
# smoke-check-world.py --structure-smoke 的结构 ID 扫描）。
# 用废弃矿井当白名单样本：密度高（每区块候选）、平原群系有效、区块 NBT 里能扫到
# 结构 ID（"minecraft:mineshaft"）。
STRUCTURE_NONE_FORMULA = (
    "{overworld=[structure:none] "
    "y=-64: minecraft:bedrock;y=-63..0: minecraft:stone;y=1..64: minecraft:air}"
)

STRUCTURE_ONLY_FORMULA = (
    "{overworld=[structure:only=mineshafts] "
    "y=-64: minecraft:bedrock;y=-63..0: minecraft:stone;y=1..64: minecraft:air}"
)

# P4.2 群系冒烟：主世界固定沙漠 / 原版群系分布（配合 smoke-check-world.py --biome-smoke）。
# 群系 id 存在区块 NBT 的群系调色板里，直接扫描即可；地形层用来确认区块确已生成。
BIOME_DESERT_FORMULA = (
    "{overworld=[biome:minecraft:desert] "
    "y=-64: minecraft:bedrock;y=-63..0: minecraft:stone;y=1..64: minecraft:air}"
)

BIOME_VANILLA_FORMULA = (
    "{overworld=[biome:vanilla] "
    "y=-64: minecraft:bedrock;y=-63..0: minecraft:stone;y=1..64: minecraft:air}"
)

# M3.3：biome 行 vanilla 群系值——放到下界的噪声生成器（flat 主世界没有 multinoise
# 参数表，vanilla 也会退化成固定群系）；整维 biome: vanilla 覆盖，日志里出现
# "formula biome uncovered" 即解析失败（否则整维覆盖不可能漏格）。
BIOME_VANILLA_VALUE_FORMULA = (
    "{overworld=y=0: minecraft:stone;y=1..64: minecraft:air}"
    "{the_nether=biome: x < -100000 ? minecraft:nether_wastes : vanilla; "
    "y=0: minecraft:bedrock;y=1..127: minecraft:netherrack;y=128..255: minecraft:air}"
)

# M3.3：biome_at 参数表查询——下界 multinoise 参数表 + 扫温参数（x*0.002）；
# 同样整维覆盖，uncovered 警告即失败；--biome-smoke 9 要求下界出现原版群系。
# 永假分支里的基线字面量是必要的：查询失败时 missingFallback 需要一个安全群系
# （否则 biome 字面量表为空会抛异常）。
BIOME_AT_VALUE_FORMULA = (
    "{overworld=y=0: minecraft:stone;y=1..64: minecraft:air}"
    "{the_nether=biome: x < -100000 ? minecraft:nether_wastes : biome_at(x * 0.002, 0.0, 0.0, 0.0, 0.0, 0.0); "
    "y=0: minecraft:bedrock;y=1..127: minecraft:netherrack;y=128..255: minecraft:air}"
)

# 临时验证（biome_at 在超平坦主世界）：右半 biome_at(x*0.002,…)，左半沙漠基线
BIOME_AT_FLAT_FORMULA = (
    "{overworld=biome: x < 0 ? minecraft:desert : biome_at(x * 0.002, 0.0, 0.0, 0.0, 0.0, 0.0); "
    "y=-64: minecraft:bedrock;y=-63..0: minecraft:stone;y=1..64: minecraft:air}"
)

# M3.5：叠加模式冒烟（--overlay-marker）——普通噪声世界（server_mode 驱动）。
# 在地表附近的石层里铺多种特征方块：
#   带 1（keep）：偶数格写海晶灯、奇数格 keep（不改）→ 验证 keep 与"后写覆盖先写"；
#   带 2（vanilla）：偶数格写白混凝土、奇数格 vanilla（还原 H1 快照）→ 验证快照还原；
#   带 3（vsolid）：深处恒固体 → 铺红砖（验证 vsolid 谓词）；
#   带 4（vis）：y=20..25 处原版石头 → 铺灰混凝土（验证 vis 方块比较）；
#   带 5（vair）：y=200 高空恒空气 → 铺玻璃（验证 vair 谓词）；
#   带 6（surf 窗口）：sy-34..sy-32（含水面基准）的固体处铺陶瓦 → 验证窗口语法；
#   带 7（切沟 + 补铺）：z∈(40,90) 的地带切掉顶部 10 个固体方块（露石），
#      [surface:vanilla+patch] 的 surface 行用 curis(stone) 把暴露面补成紫水晶。
#   带 8（sdist）：无结构区域 sdist 很大 → 品红带（验证函数接线；保护域内跳过
#      的另一半由单元测试与指令解析覆盖）。
# 地表/群系应保持原版（stone 特征方块 = 没有被当成 flat 公式填平/填满）。
OVERLAY_MARKER_FORMULA = (
    "{overworld=[terrain:vanilla] [surface:vanilla+patch] "
    "let cutz = z > 40 && z < 90;"
    "y=-30..-20: floormod(x + z, 2) == 0 ? minecraft:sea_lantern : keep;"
    "y=-40..-35: floormod(x + z, 2) == 0 ? minecraft:white_concrete : vanilla;"
    "y=-50..-45: vsolid ? minecraft:bricks : keep;"
    "y=20..25: vis(minecraft:stone) ? minecraft:gray_concrete : keep;"
    "y=200..200: vair ? minecraft:glass : keep;"
    "y=surf(-34..-32): vsolid ? minecraft:terracotta : keep;"
    "y=surf(0..9): vsolid && cutz ? minecraft:air : keep;"
    "y=surf(-24..-22): sdist(x, z) > 1000 ? minecraft:magenta_glazed_terracotta : keep;"
    "surface y=..: sd == 0 && curis(minecraft:stone) ? minecraft:amethyst_block : keep}"
)

# M3.5 验收（空叠加）：整维 `y=..: vanilla` 是恒等式——任何写入都会改变原版结果，
# 因此「首区块写入数 = 0」等价于「与原版逐方块一致」（配合原版地形生成断言）。
# 断言见 smoke-server.sh（overlay-empty 模式，需 SMOKE_DEBUG=1）。
OVERLAY_EMPTY_FORMULA = (
    "{overworld=[terrain:vanilla] y=-64..: vanilla}"
)

# 群系专属结构解锁冒烟：固定沙漠 + 只放沙漠神殿。原版在 ChunkMap 构造期按"当时的
# 群系源（平原）"过滤过结构组，沙漠神殿本不在候选里——本模组换群系后会重建结构组
# 状态；若重建失败，这里将扫不到 desert_pyramid。配合 --structure-smoke 3。
# 公式群系冒烟（配合 smoke-check-world.py --biome-smoke 3|4）：
# 3 = 两段全覆盖（低段深暗之域 / 高段沙漠，fallback 缺省 none——两行都必须生效）；
# 4 = [biome-fallback:3d]：低段深暗之域 + 其余按实际 y 交还原版分布。
BIOME_FORMULA_FORMULA = (
    "{overworld="
    "y=-64: minecraft:bedrock;"
    "y=-63..64: (x+z)%2==0 ? minecraft:sea_lantern : minecraft:polished_blackstone_bricks;"
    "biome y=-64..30: minecraft:deep_dark;"
    "biome y=31..319: minecraft:desert}"
)
BIOME_FORMULA_FALLBACK_FORMULA = (
    "{overworld=[biome-fallback:3d] "
    "y=-64: minecraft:bedrock;"
    "y=-63..64: (x+z)%2==0 ? minecraft:sea_lantern : minecraft:polished_blackstone_bricks;"
    "biome y=-64..30: minecraft:deep_dark}"
)

# 地形联动冒烟（配合 smoke-check-world.py --biome-smoke 5）：
# 群系由"公式地形"决定——blockis 命中网格点 → 冰刺之地；否则按 surfis 的
# 4 格棋盘 → 沙漠/恶地；并且要求 terrain(x,z) 确实等于表面高度 64。
# 三个函数任一失效都会导致某类群系缺失（读的是公式而不是已生成方块，
# 若实现改读区块方块，群系填充阶段还是空气 → 也会缺失）。
BIOME_TERRAIN_FORMULA = (
    "{overworld="
    "y=-64: minecraft:bedrock;"
    "y=-63..64: (floordiv(x, 4) + floordiv(z, 4)) % 2 == 0"
    " ? minecraft:sea_lantern : minecraft:polished_blackstone_bricks;"
    "y=-20: (floormod(x, 8) == 0 && floormod(z, 8) == 0)"
    " ? minecraft:gold_block : minecraft:iron_block;"
    "biome: blockis(x, z, -20, minecraft:gold_block) ? minecraft:ice_spikes"
    " : ((terrain(x, z) == 64) && surfis(x, z, minecraft:sea_lantern)"
    " ? minecraft:desert : minecraft:badlands)}"
)

# 群系回读冒烟（配合 smoke-check-world.py --biome-smoke 6）：
# [biome:vanilla] + 方块层 biomeis。
#   · biomeis 末位使用**群系表达式**（y>0 ? ocean : desert）覆盖两种路径：
#     命中（ocean → 海晶灯）与不命中（desert/plains → 不铺）；
#   · 出生区 y=64 稳定有海洋（各大版本都是）：应铺海晶灯；
#   · 沙漠在出生区不该命中：金块必须一个都不出现——这条用来抓住
#     "biomeis 恒真" 的实现错误；
#   · 其余铺磨制黑石砖（只作背景，不作为判定项）。
BIOME_BIOMEIS_FORMULA = (
    "{overworld=[biome:vanilla] "
    "y=-64: minecraft:bedrock;"
    "y=-63..64: biomeis(x, z, 64, y > 0 ? minecraft:desert : minecraft:plains) ? minecraft:gold_block"
    " : (biomeis(x, z, 64, y > 0 ? minecraft:ocean : minecraft:desert) ? minecraft:sea_lantern"
    " : minecraft:polished_blackstone_bricks)}"
)

# 自然世界冒烟（配合 smoke-check-world.py --biome-smoke 7）：
# 噪声起伏地形（fbm2 定高）+ 交错洞穴（noise3 掏洞）+ y 变量写地层。
# 检查只看"地表材质方块都在"；地表起伏与洞穴空气率用 dump-biomes.py stats 复核。
NATURAL_FORMULA = (
    "{overworld="
    "let cont = fbm2(x, z, 180, 3, 1);"
    "let h = 64 + cont * 26;"
    "let cave = noise3(x, y, z, 70, 7);"
    "y=-64..319: (cave > 0.35 && y < h - 4) ? minecraft:air"
    " : (y <= h ? (y > h - 4 ? minecraft:grass_block : minecraft:stone) : minecraft:air)}"
)

# 雕刻器 A/B 冒烟（--carvers / --carvers-off；配合 smoke-check-world.py --biome-smoke 8）：
# 普通（噪声）世界里铺整块石头到 y=80，开/关原版雕刻器各跑一次，
# 用 dump-biomes.py stats 比较 y∈[10,70] 的空气率（开 = 有隧道/峡谷被挖出）。
CARVERS_FORMULA = (
    "{overworld=[carvers:vanilla] y=-64..319: y <= 80 ? minecraft:stone : minecraft:air}"
)
CARVERS_OFF_FORMULA = (
    "{overworld=[carvers:none] y=-64..319: y <= 80 ? minecraft:stone : minecraft:air}"
)
# 1.3.2-beta.4：[carvers:vanilla-ew]（except water）——放行雕刻但跳过水方块本身。
# 西半（x<0）无水：雕刻照常发生；东半（x>=0）水面 y=41..62：水必须保持完整
# （水内出现任何非水方块 = 水被雕刻）；水下固体（海床）允许被雕刻。
# [features:none] [structure:none]：排除海草/沉船等对水段核验的干扰。
CARVERS_EW_FORMULA = (
    "{overworld=[carvers:vanilla-ew] [features:none] [structure:none] "
    "y=-64..319: y <= 40 ? minecraft:stone : (x >= 0 && y <= 62 ? minecraft:water : minecraft:air)}"
)

# 超平坦雕刻器 A/B 冒烟（--flat-carvers / --flat-carvers-off；flat_plus 预设）：
# 公式与上面相同，区别在 level-type——验证 1.2.6 超平坦世界的"干燥代理"雕刻
# （FlatLevelSource 的 applyCarvers 原本为空实现）。同样用 stats 比较空气率。
FLAT_CARVERS_FORMULA = CARVERS_FORMULA
FLAT_CARVERS_OFF_FORMULA = CARVERS_OFF_FORMULA

BIOME_STRUCTURES_FORMULA = (
    "{overworld=[biome:minecraft:desert] [structure:only=desert_pyramids] "
    "y=-64: minecraft:bedrock;y=-63..0: minecraft:stone;y=1..64: minecraft:air}"
)

# P4.3 特性冒烟：下界固定玄武岩三角洲（原始群系表内 → 装饰合法），
# all = 原版装饰（黑石/玄武岩斑块、荧石、岩浆块…）；none = 只留公式地形。
# 默认公式行不含装饰方块，核验靠 smoke-check-world.py --features-smoke。
FEATURES_ALL_FORMULA = (
    "{the_nether=[biome:minecraft:basalt_deltas] [features:all] "
    "y=0..60: minecraft:netherrack;y=61..127: minecraft:air}"
)

FEATURES_NONE_FORMULA = (
    "{the_nether=[biome:minecraft:basalt_deltas] [features:none] "
    "y=0..60: minecraft:netherrack;y=61..127: minecraft:air}"
)

# 1.3.0 新函数冒烟（--m1-functions；配合 smoke-check-world.py --require-all）：
# 元组 let（warp2）、噪声变体（ridge/fbm2e）、循环（sum）、空间助手
# （terrace/slope/isodist/clamp）与 seedhash 逐块噪声混合；
# 两种特征方块（海晶灯 / 黑石砖）都要出现才算通过。
M1_FORMULA = (
    "{overworld="
    "let (u, v) = warp2(x, z, 300, 40, 7);"
    "let n = fbm2(x + u, z + v, 600, 4, 1);"
    "let ridge = ridged2(x + u, z + v, 500, 3, 5, 2);"
    "let cached = cache2d(fbm2(x, z, 700, 3, 13), 8)"
    " + cache3d(noise3(x, y, z, 150, 5), 4, 8, 4);"
    "let extra = fbm2e(x, z, 800, 3, 2, 2) * 0.25"
    " + sum(k, 0, 2, k) * 0.02 + terrace(n, 4, 6) * 0.1"
    " + slope(fbm2(x, z, 200, 2, 11)) * 5"
    " + clamp(isodist(n - 0.2), 0, 3) * 0.05"
    " + (blur2(noise2(x, z, 120, 21), 2) > noise2(x, z, 120, 21) ? 0.25 : -0.25);"
    "y=-64..: (n + ridge * 0.5 + cached * 0.2 + extra + (seedhash(x, z, 3) - 0.5) * 0.8) > 0.35"
    " ? minecraft:sea_lantern : minecraft:polished_blackstone_bricks}"
)

# 1.3.1 表面通道冒烟（--surface；配合 smoke-check-world.py 的显式 --expect）：
# 台阶地形（h=60/64 交替，每 8 格一级）+ 低地灌水；表面规则按 sd/wd/slope 分层：
# 顶面海晶灯 / 第二层黑石砖 / 水下顶面蛙明灯 / 陡坡第三层玻璃；其余 keep。
SURFACE_FORMULA = (
    "{overworld="
    "let h = 60 + (floormod(floordiv(x, 8), 2) == 0 ? 0 : 4);"
    "y=-64..: y <= h ? minecraft:stone : (y <= 64 && h < 64 ? minecraft:water : minecraft:air);"
    "surface y=55..70: sd == 0 ? minecraft:sea_lantern : keep;"
    "surface y=55..70: sd == 1 ? minecraft:polished_blackstone_bricks : keep;"
    "surface y=55..70: wd >= 1 && sd == 0 ? minecraft:ochre_froglight : keep;"
    "surface y=55..70: slope > 2 && sd == 2 ? minecraft:glass : keep}"
)

# 1.3.1 河网冒烟（--rivernet；配合显式 --expect 的 沙/水）：
# rivernet 刻河道（河床沙 + 水体），基础地形为多倍频起伏；无河处保持原地形。
# coarse 用"以原点为中心的径向坡"（70 - r²·1e-5）：保证原点附近必有河道，
# 避免不同节点出生点准备区块数不同（覆盖面积不同）导致抽查不稳定。
RIVERNET_FORMULA = (
    "{overworld="
    "let (d, w, s, o) = rivernet(70 - (x * x + z * z) * 0.00001, 192, 7, 2.5, 0.5, 16);"
    "let base = 60 + fbm2(x, z, 600, 4, 1) * 20;"
    "let bed = min(base, s - 4 + max(d - w, 0) * 0.7);"
    "y=-64..: y <= bed ? (d < w ? minecraft:sand : minecraft:stone)"
    " : (d < w && y <= s ? minecraft:water : minecraft:air)}"
)

# 1.3.2 共享气候冒烟（--climate；配合显式 --expect 的两种特征方块）：
# 反对称比较 climate(temperature, x, z) > climate(temperature, z, x)：
# 真实视图下差分反对称、两侧符号都会出现（两种特征方块齐全）；
# 无视图时两侧同为 0 → 全为黑石砖，核验直接失败（防止假阳性）。
# forceload 扩大采样范围（启动只完整生成出生区块半径 2）。
CLIMATE_FORMULA = (
    "{overworld="
    "let h = 60 + fbm2(x, z, 600, 4, 1) * 12;"
    "y=-64..: y <= h ? (climate(temperature, x, z) > climate(temperature, z, x)"
    " ? minecraft:sand : minecraft:polished_blackstone_bricks) : minecraft:air}"
)

# 1.3.2 原版数据冒烟（--dfnoise；配合显式 --expect 的六种特征方块）：
# df()/noise() 各用一次反对称比较（x/z 互换），vheight() 用 x/z 互换的两列高度比较；
# 真实视图下三种比较都会产生两种结果（六种特征方块齐全），无视图/注册名缺失时
# 只剩单一分支，核验直接失败（防假阳性）。
# df/noise 单点求值很贵，用 cache2d 摊薄；vheight 每列要做几十次 finalDensity 采样，
# 限定在 8×8 格点上调用（真实用法建议同理），避免 256 区块 forceload 超出看门狗。
DFNOISE_FORMULA = (
    "{overworld="
    "let h = 60 + fbm2(x, z, 600, 4, 1) * 12;"
    "let r1 = cache2d(df(minecraft:overworld/ridges, x, 64, z), 4);"
    "let r2 = cache2d(df(minecraft:overworld/ridges, z, 64, x), 4);"
    "let n1 = cache2d(noise(minecraft:temperature, x, 64, z), 4);"
    "let n2 = cache2d(noise(minecraft:temperature, z, 64, x), 4);"
    "y=-64..: y <= h ? (y <= 40"
    " ? (r1 > r2 ? minecraft:sand : minecraft:bricks)"
    " : y <= 60 ? (n1 > n2 ? minecraft:gravel : minecraft:clay)"
    " : ((floormod(x, 8) == 0 && floormod(z, 8) == 0)"
    "   ? (vheight(x, z) != vheight(z, x) ? minecraft:white_concrete : minecraft:gray_concrete)"
    "   : minecraft:gray_concrete)) : minecraft:air}"
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
    spawn_formula = "--spawn-formula" in sys.argv
    open_ranges = "--open-ranges" in sys.argv
    water = "--water" in sys.argv
    river = "--river" in sys.argv
    dimension_formula = "--dimension-formula" in sys.argv
    dimension_alias = "--dimension-alias" in sys.argv
    marker_formula = "--marker-formula" in sys.argv
    structure_none = "--structure-none" in sys.argv
    structure_only = "--structure-only" in sys.argv
    biome_desert = "--biome-desert" in sys.argv
    biome_vanilla = "--biome-vanilla" in sys.argv
    biome_vanilla_value = "--biome-vanilla-value" in sys.argv
    biome_at_value = "--biome-at-value" in sys.argv
    biome_at_flat = "--biome-at-flat" in sys.argv
    overlay_marker = "--overlay-marker" in sys.argv
    overlay_empty = "--overlay-empty" in sys.argv
    biome_structures = "--biome-structures" in sys.argv
    biome_formula = "--biome-formula" in sys.argv
    biome_formula_fallback = "--biome-formula-fallback" in sys.argv
    biome_terrain = "--biome-terrain" in sys.argv
    biome_biomeis = "--biome-biomeis" in sys.argv
    natural = "--natural" in sys.argv
    carvers = "--carvers" in sys.argv
    carvers_off = "--carvers-off" in sys.argv
    carvers_ew = "--carvers-ew" in sys.argv
    flat_carvers = "--flat-carvers" in sys.argv
    flat_carvers_off = "--flat-carvers-off" in sys.argv
    flat_carvers_ew = "--flat-carvers-ew" in sys.argv
    features_all = "--features-all" in sys.argv
    features_none = "--features-none" in sys.argv
    m1_functions = "--m1-functions" in sys.argv
    surface = "--surface" in sys.argv
    rivernet = "--rivernet" in sys.argv
    climate = "--climate" in sys.argv
    dfnoise = "--dfnoise" in sys.argv
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
    if carvers or carvers_off or carvers_ew or spawn_formula or overlay_marker or overlay_empty:
        # 雕刻器/出生点/叠加模式只存在于噪声生成器：必须用普通世界类型。
        # 出生点专项同理：超平坦世界的出生点固定在 (0,0)，噪声世界的出生点随种子变化，
        # 才能区分"读到真实出生点"与"恒为 0"。
        props["level-type"] = "minecraft\\:normal"
    if structure_none or structure_only:
        # 结构冒烟要真正生成结构：
        # - 用我们自己的预设（结构覆盖表已移除 → 候选=全部结构）；
        # - 打开结构开关（默认冒烟是关结构，避免干扰方块核验）。
        props["level-type"] = "ohmyworld\\:flat_plus"
        props["generate-structures"] = "true"
    elif biome_desert or biome_vanilla or biome_vanilla_value or biome_at_value \
            or biome_structures or biome_formula or biome_formula_fallback \
            or biome_terrain or biome_biomeis or natural or features_all or features_none \
            or flat_carvers or flat_carvers_off or flat_carvers_ew or m1_functions or surface \
            or rivernet or climate or dfnoise:
        # 群系/特性/超平坦雕刻冒烟：用我们自己的预设（三维度齐全，下界为噪声生成器）。
        props["level-type"] = "ohmyworld\\:flat_plus"
    if biome_structures:
        # 结构解锁冒烟要真正生成结构
        props["generate-structures"] = "true"
    props["server-port"] = str(port)
    prop_file.write_text("\n".join(f"{k}={v}" for k, v in props.items()) + "\n", encoding="utf-8")

    if dimension_formula:
        formula = DIMENSION_FORMULA
    elif dimension_alias:
        formula = DIMENSION_ALIAS_FORMULA
    elif marker_formula:
        formula = DIMENSION_FORMULA
    elif structure_none:
        formula = STRUCTURE_NONE_FORMULA
    elif structure_only:
        formula = STRUCTURE_ONLY_FORMULA
    elif biome_desert:
        formula = BIOME_DESERT_FORMULA
    elif biome_vanilla:
        formula = BIOME_VANILLA_FORMULA
    elif biome_vanilla_value:
        formula = BIOME_VANILLA_VALUE_FORMULA
    elif biome_at_value:
        formula = BIOME_AT_VALUE_FORMULA
    elif biome_at_flat:
        formula = BIOME_AT_FLAT_FORMULA
    elif overlay_marker:
        formula = OVERLAY_MARKER_FORMULA
    elif overlay_empty:
        formula = OVERLAY_EMPTY_FORMULA
    elif biome_structures:
        formula = BIOME_STRUCTURES_FORMULA
    elif biome_formula:
        formula = BIOME_FORMULA_FORMULA
    elif biome_formula_fallback:
        formula = BIOME_FORMULA_FALLBACK_FORMULA
    elif biome_terrain:
        formula = BIOME_TERRAIN_FORMULA
    elif biome_biomeis:
        formula = BIOME_BIOMEIS_FORMULA
    elif natural:
        formula = NATURAL_FORMULA
    elif carvers:
        formula = CARVERS_FORMULA
    elif carvers_off:
        formula = CARVERS_OFF_FORMULA
    elif carvers_ew:
        formula = CARVERS_EW_FORMULA
    elif flat_carvers:
        formula = FLAT_CARVERS_FORMULA
    elif flat_carvers_off:
        formula = FLAT_CARVERS_OFF_FORMULA
    elif flat_carvers_ew:
        formula = CARVERS_EW_FORMULA
    elif features_all:
        formula = FEATURES_ALL_FORMULA
    elif features_none:
        formula = FEATURES_NONE_FORMULA
    elif seed_formula:
        formula = SEED_FORMULA
    elif spawn_formula:
        formula = SPAWN_FORMULA
    elif open_ranges:
        formula = OPEN_RANGES_FORMULA
    elif water:
        formula = WATER_FORMULA
    elif river:
        formula = RIVER_FORMULA
    elif m1_functions:
        formula = M1_FORMULA
    elif surface:
        formula = SURFACE_FORMULA
    elif rivernet:
        formula = RIVERNET_FORMULA
    elif climate:
        formula = CLIMATE_FORMULA
    elif dfnoise:
        formula = DFNOISE_FORMULA
    else:
        formula = FORMULA

    config = server / "config" / "ohmyworld.json"
    wrote_formula = (force or seed_formula or spawn_formula or open_ranges or water or river
                     or dimension_formula or dimension_alias
                     or marker_formula or structure_none or structure_only
                     or biome_desert or biome_vanilla or biome_vanilla_value or biome_at_value or biome_at_flat
                     or overlay_marker or overlay_empty
                     or biome_structures
                     or biome_formula or biome_formula_fallback or biome_terrain or biome_biomeis or natural \
                     or carvers or carvers_off or carvers_ew
                     or flat_carvers or flat_carvers_off or flat_carvers_ew
                     or features_all or features_none or m1_functions or surface or rivernet or climate
                     or dfnoise
                     or not config.exists())
    if not wrote_formula:
        # 既有配置若本身就是「冒烟公式」（含特征方块），允许升级为本工具的最新版本；
        # 只有作者手写/巨构公式才原样保留。
        try:
            existing = config.read_text(encoding="utf-8")
            if any(marker in existing for marker in ("sea_lantern", "polished_blackstone_bricks", "white_concrete")):
                wrote_formula = True
        except Exception:
            pass
    # 临时/自定义公式：SMOKE_FORMULA_TEXT 环境变量优先于一切预设（便于手工实验；
    # 支持多行——JSON 写入已改用标准转义）。用后检查预期自行以 --expect 给出。
    custom_text = os.environ.get("SMOKE_FORMULA_TEXT", "").strip()
    if custom_text:
        formula = custom_text
        wrote_formula = True
    if wrote_formula:
        config.parent.mkdir(parents=True, exist_ok=True)
        debug = "true" if os.environ.get("SMOKE_DEBUG") == "1" else "false"
        import json as _json
        config.write_text(_json.dumps({
            "server_mode": False if marker_formula else True,
            "debug_logs": debug == "true",
            "formula": formula,
        }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    if marker_formula:
        # 预置世界 marker：server_mode=false 时由 WorldLoadHandler 的 marker 恢复路径接管
        world = server / "world"
        world.mkdir(parents=True, exist_ok=True)
        (world / "ohmyworld_marker.txt").write_text(DIMENSION_FORMULA, encoding="utf-8")

    if marker_formula:
        label = "marker 驱动冒烟（预置分节 marker、server_mode=false）"
    elif dimension_formula:
        label = "使用分节冒烟公式（1：overworld+the_nether，末地缺失）"
    elif dimension_alias:
        label = "使用分节冒烟公式（2：无 overworld + the_end 别名）"
    elif structure_none:
        label = "使用结构冒烟公式（下界 [structure:none]）"
    elif structure_only:
        label = "使用结构冒烟公式（下界 [structure:only=nether_fossils]）"
    elif biome_desert:
        label = "使用群系冒烟公式（主世界 [biome:minecraft:desert]）"
    elif biome_vanilla:
        label = "使用群系冒烟公式（主世界 [biome:vanilla]）"
    elif biome_structures:
        label = "使用群系结构冒烟公式（主世界 [biome:desert] + [structure:only=desert_pyramids]）"
    elif biome_formula:
        label = "使用公式群系冒烟（主世界 biome 行两段全覆盖、fallback=none）"
    elif biome_formula_fallback:
        label = "使用公式群系冒烟（主世界 biome 行 + [biome-fallback:3d]）"
    elif biome_terrain:
        label = "使用地形联动冒烟（biome 行调用 terrain/surfis/blockis）"
    elif biome_biomeis:
        label = "使用群系回读冒烟（[biome:vanilla] + 方块层 biomeis）"
    elif natural:
        label = "使用自然世界冒烟（噪声地形 + 洞穴 + y 变量）"
    elif carvers:
        label = "使用雕刻器冒烟（普通世界 + [carvers:vanilla]）"
    elif carvers_off:
        label = "使用雕刻器对照冒烟（普通世界 + [carvers:none]）"
    elif carvers_ew:
        label = "使用雕刻器水体保护冒烟（普通世界 + [carvers:vanilla-ew]）"
    elif flat_carvers:
        label = "使用超平坦雕刻器冒烟（flat_plus + [carvers:vanilla] 干燥代理）"
    elif flat_carvers_off:
        label = "使用超平坦雕刻器对照冒烟（flat_plus + [carvers:none]）"
    elif flat_carvers_ew:
        label = "使用超平坦雕刻器水体保护冒烟（flat_plus + [carvers:vanilla-ew] 干燥代理）"
    elif features_all:
        label = "使用特性冒烟公式（下界 [features:all] + 玄武岩三角洲）"
    elif features_none:
        label = "使用特性冒烟公式（下界 [features:none] + 玄武岩三角洲）"
    elif seed_formula:
        label = "使用种子专项冒烟公式"
    elif spawn_formula:
        label = "使用出生点专项冒烟公式（spawnx/spawnz 圆盘）"
    elif open_ranges:
        label = "使用开区间层冒烟公式（y=.. / y=..60 / y=61..）"
    elif water:
        label = "使用水域冒烟公式（spline 地形 + waterline 水位）"
    elif river:
        label = "使用河流冒烟公式（噪声零等值线河谷 + 水位淹没）"
    elif overlay_marker:
        label = "使用 [terrain:vanilla] 叠加模式冒烟公式"
    elif overlay_empty:
        label = "使用 [terrain:vanilla] 空叠加验收公式（整维 vanilla）"
    elif wrote_formula:
        label = "使用冒烟公式"
    else:
        label = "保留已有公式"
    print(f"[smoke-config] {server}: level-type={props.get('level-type', '?')}, port={port}, {label}")
    print(f"SMOKE_FORMULA={1 if wrote_formula else 0}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
