# Oh My World

**Oh My World** 是一个 Minecraft NeoForge 模组。通过数学表达式定义超平坦世界的地形图案——无需编程，使用算术、三角函数、噪声和随机函数即可生成无限种地形。

[English Version](README.md)

---

## 功能

- 创建世界界面新增 **"公式化生成"** 世界类型
- **表达式引擎**：内置 30+ 数学函数（`sin`/`cos`/`tan`、平滑噪声 `smooth`、伪随机 `rng`/`rand` 等）
- **循环层**：在指定 y 范围内按序列循环铺设不同表达式的结果
- 支持棋盘格、条纹、正弦波、有机噪声地形等图案
- **自定义公式编辑器**，支持保存/加载到本地文件
- **跨会话持久化**——图案配置随世界存档保存

## 环境要求

- Minecraft **1.21.1**
- **NeoForge**（1.21.1 适用）

## 安装

1. 为 Minecraft 1.21.1 安装 NeoForge
2. 从 [Releases](<!-- TODO -->) 下载 `oh-my-world-1.0.0.jar`
3. 放入 `mods/` 文件夹
4. 使用 NeoForge 配置文件启动 Minecraft

## 使用方式

1. 新建世界 → **世界** 标签页 → **世界类型** → 选择 **"公式化生成"**
2. 点击 **"自定义"** 打开公式编辑器
3. 输入公式（参考下方的公式编写说明），点击 **完成**
4. 创建世界，享受自定义地形！

### 公式编写说明

模组首次启动时会自动在 `<游戏目录>/ohmyworld/` 中创建 `README_zh_cn.md`（中文）和 `README_en_us.md`（英文），内含完整语法参考和示例。

**快速示例：**

```
# 64 层 1×1 棋盘格
y=0: minecraft:bedrock;y=1..64: (x+z)%2==0 ? minecraft:white_concrete : minecraft:gray_concrete

# 循环层：3 基岩 + 2 泥土 + 1 棋盘格（每 6 层循环）
y=0..64: 3*[minecraft:bedrock],2*[minecraft:dirt],1*[(x+z)%2==0 ? minecraft:white_concrete : minecraft:gray_concrete]

# 平滑噪声地形
y=1..64: smooth(x,z,0.1,0.1)>0.5 ? minecraft:white_concrete : minecraft:gray_concrete

# 随机方块池
y=1..64: rand(minecraft:stone, minecraft:dirt, minecraft:oak_planks)
```

## 从源码构建

```bash
# 需要 JDK 21
git clone <!-- TODO -->
cd ohmyworld
cp gradle.properties.example gradle.properties
# 编辑 gradle.properties：设置 org.gradle.java.home 为你的 JDK 21 路径
./gradlew build
# 产物：build/libs/oh-my-world-1.0.0.jar
```

## 许可协议

MIT License — 详见 [LICENSE](LICENSE)
