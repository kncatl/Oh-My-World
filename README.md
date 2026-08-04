# Oh My World

**Oh My World** is a Minecraft mod (NeoForge / Fabric) that lets you create custom flat worlds with mathematical expressions. Define terrain patterns using arithmetic, trigonometry, and random functions — no coding required.

[中文版本](README_zh_cn.md)

---

## Features

- **Formula Generator** world type in the Create World screen
- **Expression engine** with 24 built-in functions: arithmetic, trigonometry (`sin`, `cos`, `tan`), integer math (`floordiv`, `floormod`), pseudo-random (`rand`, `randexcept`)
- **Cyclic layer patterns** — define repeating sequences within a y-range
- **Checkboard, stripes, sine waves, random terrain** and more
- **Custom formula editor** with save/load to local files
- **Live error feedback** — parse and semantic errors (unknown functions/blocks, wrong argument counts, etc.) are shown directly in the editor, and invalid formulas cannot be applied
- **Cross-session persistence** — your pattern is stored with the world

## Requirements

| Minecraft | Loader |
| --- | --- |
| 1.21.1 | NeoForge |
| 1.21.11 | NeoForge / Fabric |

Requires **Java 21**.

## Installation

1. Install NeoForge or Fabric (with Fabric API) for the Minecraft version from the table above
2. Download the jar for your `mc-version-loader` from [Releases](https://github.com/kncatl/Oh-My-World/releases)
3. Place the jar in your `mods/` folder
4. Launch Minecraft

## Usage

1. Create a new world → **World** tab → **World Type** → select **Formula Generator**
2. Click **Customize** to open the formula editor
3. Enter your formula (see guide below), then click **Done**
4. Create the world and enjoy your custom terrain!

### Dedicated Servers

- Dedicated servers have no world-creation UI, so the formula marker cannot be written
  from the editor. Set **`server_mode: true`** in `config/ohmyworld.json` instead; the
  formula is then applied globally to all superflat worlds on the server (non-flat worlds
  are unaffected).
- Without `server_mode`, worlds created/specified with the `ohmyworld:flat_plus` preset
  on a dedicated server generate as vanilla superflat.
- With `server_mode`, editing the formula in the config file takes effect in about
  5 seconds **without restarting the server** (only affects newly generated chunks).

### Formula Guide

A comprehensive formula writing guide (`README_en_us.md` / `README_zh_cn.md`) is
automatically created in `<game_dir>/ohmyworld/` on first launch. On mod upgrades the
files are auto-updated as long as you have not edited them yourself.

**Quick examples:**

```
# Checkerboard from the world bottom (-64) up to 64
y=-64: minecraft:bedrock;y=-63..64: (x+z)%2==0 ? minecraft:white_concrete : minecraft:gray_concrete

# Cyclic: 3 bedrock + 2 dirt + 1 checker (repeating every 6 layers)
y=-64..64: 3*[minecraft:bedrock],2*[minecraft:dirt],1*[(x+z)%2==0 ? minecraft:white_concrete : minecraft:gray_concrete]

# Random pool
y=-64..64: rand(minecraft:stone, minecraft:dirt, minecraft:oak_planks)
```

## Building from Source

```bash
# Requires JDK 21
git clone https://github.com/kncatl/Oh-My-World.git
cd Oh-My-World
# gradle.properties is tracked; edit its local JDK/proxy settings only if needed
# Keep credentials and machine-specific overrides in ~/.gradle/gradle.properties
# Builds the currently active version (see versions/ directory, e.g. 1.21.11-fabric)
./gradlew buildActive
# Output: versions/<version>-<loader>/build/libs/oh-my-world-<version>-<loader>-<modversion>.jar
# Switch the active version by editing .sc_active_version, then re-run gradlew
```

Note: `./gradlew build` / `./gradlew clean` without a project path act on **all three
versions** (`clean` wipes every version's build directory, including jars). Use
`buildActive` to build a single version; after a successful build the console prints
the full path of the produced jar.

## License

MIT License — see [LICENSE](LICENSE) for details.
