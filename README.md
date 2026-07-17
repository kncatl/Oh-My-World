# Oh My World

**Oh My World** is a Minecraft NeoForge mod that lets you create custom flat worlds with mathematical expressions. Define terrain patterns using arithmetic, trigonometry, noise, and random functions — no coding required.

[中文版本](README_zh_cn.md)

---

## Features

- **Formula Generator** world type in the Create World screen
- **Expression engine** with 23 built-in functions: arithmetic, trigonometry (`sin`, `cos`, `tan`), integer math (`floordiv`, `floormod`), pseudo-random (`rand`, `randexcept`)
- **Cyclic layer patterns** — define repeating sequences within a y-range
- **Checkboard, stripes, sine waves, random terrain** and more
- **Custom formula editor** with save/load to local files
- **Live parse error feedback** — formula errors are shown directly in the editor
- **Cross-session persistence** — your pattern is stored with the world

## Requirements

- Minecraft **1.21.1**
- **NeoForge** (1.21.1 compatible)

## Installation

1. Install NeoForge for Minecraft 1.21.1
2. Download `oh-my-world-1.0.0.jar` from [Releases](https://github.com/kncatl/Oh-My-World/releases)
3. Place the jar in your `mods/` folder
4. Launch Minecraft with the NeoForge profile

## Usage

1. Create a new world → **World** tab → **World Type** → select **Formula Generator**
2. Click **Customize** to open the formula editor
3. Enter your formula (see guide below), then click **Done**
4. Create the world and enjoy your custom terrain!

### Formula Guide

A comprehensive formula writing guide (`README_en_us.md` / `README_zh_cn.md`) is automatically created in `<game_dir>/ohmyworld/` on first launch.

**Quick examples:**

```
# 64-layer checkerboard
y=0: minecraft:bedrock;y=1..64: (x+z)%2==0 ? minecraft:white_concrete : minecraft:gray_concrete

# Cyclic: 3 bedrock + 2 dirt + 1 checker (repeating every 6 layers)
y=0..64: 3*[minecraft:bedrock],2*[minecraft:dirt],1*[(x+z)%2==0 ? minecraft:white_concrete : minecraft:gray_concrete]

# Random pool
y=1..64: rand(minecraft:stone, minecraft:dirt, minecraft:oak_planks)
```

## Building from Source

```bash
# Requires JDK 21
git clone https://github.com/kncatl/Oh-My-World.git
cd Oh-My-World
cp gradle.properties.example gradle.properties
# Edit gradle.properties: set org.gradle.java.home to your JDK 21 path (if not on PATH)
./gradlew build
# Output: build/libs/oh-my-world-1.0.0.jar
```

## License

MIT License — see [LICENSE](LICENSE) for details.