# Oh My World

**Oh My World** 是一个 Minecraft 模组（NeoForge / Fabric 双加载器）。通过数学表达式定义超平坦世界的地形图案——无需编程，使用算术、三角函数和随机函数即可生成无限种地形。

[English Version](README.md)

---

## 功能

- 创建世界界面新增 **"公式化生成"** 世界类型
- **表达式引擎**：内置 24 个数学函数（`sin`/`cos`/`tan`、整数运算 `floordiv`/`floormod`、伪随机 `rand`/`randexcept` 等）
- **循环层**：在指定 y 范围内按序列循环铺设不同表达式的结果
- 支持棋盘格、条纹、正弦波、随机地形等图案
- **自定义公式编辑器**，支持保存/加载到本地文件
- **实时错误反馈**——公式解析与语义错误（未知函数、未知方块、参数个数等）直接在编辑器中显示，有错误时无法应用公式
- **跨会话持久化**——图案配置随世界存档保存

## 环境要求

| Minecraft | 加载器 |
| --- | --- |
| 1.21.1 | NeoForge |
| 1.21.11 | NeoForge / Fabric |

需要 **Java 21**。

## 安装

1. 按上表为对应 Minecraft 版本安装 NeoForge 或 Fabric（含 Fabric API）
2. 从 [Releases](https://github.com/kncatl/Oh-My-World/releases) 下载对应 `mc版本-加载器` 的 jar
3. 放入 `mods/` 文件夹
4. 启动 Minecraft

## 使用方式

1. 新建世界 → **世界** 标签页 → **世界类型** → 选择 **"公式化生成"**
2. 点击 **"自定义"** 打开公式编辑器
3. 输入公式（参考下方的公式编写说明），点击 **完成**
4. 创建世界，享受自定义地形！

### 专用服务器

- 专用服务器没有创建世界界面，无法通过编辑器写入公式标记（marker），请在
  `config/ohmyworld.json` 中设置 **`server_mode: true`**，公式将全局应用于服务器
  的所有超平坦世界（对非超平坦世界无影响）。
- 不开启 `server_mode` 时，专用服务器上以 `ohmyworld:flat_plus` 预设创建/指定的世界
  将按原版超平坦方式生成。
- `server_mode` 下修改配置文件中的公式**无需重启服务器**，约 5 秒后自动生效
  （仅影响之后新生成的区块）。

### 公式编写说明

模组首次启动时会自动在 `<游戏目录>/ohmyworld/` 中创建 `README_zh_cn.md`（中文）
和 `README_en_us.md`（英文），内含完整语法参考和示例。模组升级后，只要你不曾
手动修改过这些文件，它们会自动更新为新版内容。

**快速示例：**

```
# 1×1 棋盘格（从世界底部 -64 铺到 64）
y=-64: minecraft:bedrock;y=-63..64: (x+z)%2==0 ? minecraft:white_concrete : minecraft:gray_concrete

# 循环层：3 基岩 + 2 泥土 + 1 棋盘格（每 6 层循环）
y=-64..64: 3*[minecraft:bedrock],2*[minecraft:dirt],1*[(x+z)%2==0 ? minecraft:white_concrete : minecraft:gray_concrete]

# 随机方块池
y=-64..64: rand(minecraft:stone, minecraft:dirt, minecraft:oak_planks)
```

## 从源码构建

```bash
# 需要 JDK 21
git clone https://github.com/kncatl/Oh-My-World.git
cd Oh-My-World
# gradle.properties 已纳入版本控制；如有需要只编辑本机 JDK/代理设置
# 凭据和机器专属覆盖项请放到 ~/.gradle/gradle.properties
# 构建当前激活版本（versions/ 目录下的 1.21.11-fabric 等）
./gradlew buildActive
# 产物：versions/<版本>-<加载器>/build/libs/oh-my-world-<版本>-<加载器>-<模组版本>.jar
# 切换激活版本：编辑 .sc_active_version 后重新运行 gradlew
```

注意：`./gradlew build` / `./gradlew clean` 不带项目路径时会作用于**全部三个版本**
（clean 会清空所有版本的 build 目录，包括 jar）。只构建一个版本请用 `buildActive`，
构建成功后控制台会直接打印 jar 的完整路径。

## 项目链接

- [GitHub 仓库](https://github.com/kncatl/Oh-My-World)
- [问题反馈](https://github.com/kncatl/Oh-My-World/issues)
- [版本发布](https://github.com/kncatl/Oh-My-World/releases)
- [CurseForge](https://www.curseforge.com/minecraft/mc-mods/oh-my-world)

## 许可协议

MIT License — 详见 [LICENSE](LICENSE)
