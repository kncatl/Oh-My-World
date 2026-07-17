# Oh My World

**Turn mathematical expressions into world generation.**

Oh My World is a NeoForge mod for Minecraft 1.21.1 that lets you define terrain using a powerful expression engine. Write formulas with arithmetic, trigonometry, noise, and random functions — and watch them transform into infinite custom landscapes.

---

## What You Can Create

- **Endless checkerboard patterns** — alternating blocks in any grid size
- **Mathematical function graphs** — sine waves, circles, spirals rendered in blocks
- **3D models and structures** — cyclic layers stacked with precision
- **Randomized block pools** — procedural variety with seeded determinism
- **Anything you can express** — the expression engine supports 23 functions

---

## Built-in Expression Engine

| Category | Functions |
|----------|-----------|
| Arithmetic | `+` `-` `*` `/` `%` |
| Comparison | `==` `!=` `<` `>` `<=` `>=` |
| Logic | `&&` `\|\|` `!` `? :` |
| Math | `abs` `max` `min` `floor` `ceil` `round` `sign` `sqrt` `pow` `exp` `log` `log10` |
| Trigonometry | `sin` `cos` `tan` `asin` `acos` `atan` `todeg` `torad` |
| Random Blocks | `rand()` — all blocks · `rand(b1,b2)` — whitelist · `randexcept(b1,b2)` — blacklist |
| Grid Tools | `floordiv` `floormod` — correct handling for negative coordinates |
| Variables | `x` (world X) · `z` (world Z) · `ly` (layer offset) |

---

## Quick Examples

```
# 64-layer checkerboard
y=0: minecraft:bedrock;y=1..64: (x+z)%2==0 ? minecraft:white_concrete : minecraft:gray_concrete

# 3×3 grid with center swap
y=1..64: (floordiv(x,3)+floordiv(z,3))%2==0
  ? (floormod(x,3)==1 && floormod(z,3)==1 ? minecraft:gray_concrete : minecraft:white_concrete)
  : (floormod(x,3)==1 && floormod(z,3)==1 ? minecraft:white_concrete : minecraft:gray_concrete)

# Sine wave terrain
y=1..64: sin(torad(x*10))>0 ? minecraft:white_concrete : minecraft:gray_concrete

# Random block pool
y=1..64: rand(minecraft:stone, minecraft:dirt, minecraft:oak_planks)

# Cyclic repeating pattern (3 bedrock + 2 dirt + 1 checker, every 6 layers)
y=0..64: 3*[minecraft:bedrock],2*[minecraft:dirt],1*[(x+z)%2==0 ? minecraft:white_concrete : minecraft:gray_concrete]
```

---

## How to Use

1. Create a new world → **World** tab → **World Type** → select **Formula Generator**
2. Click **Customize** to open the formula editor
3. Enter your formula and a save name, then click **Done**
4. Create the world — every chunk follows your rules

A comprehensive formula writing guide (`README_zh_cn.md` / `README_en_us.md`) is automatically created in `<game_dir>/ohmyworld/` on first launch.

---

## Requirements

- Minecraft **1.21.1**
- **NeoForge**

---

## License & Source

This mod is open source under the MIT License.

- [GitHub Repository](https://github.com/kncatl/Oh-My-World)
- [Issue Tracker](https://github.com/kncatl/Oh-My-World/issues)
- [Releases](https://github.com/kncatl/Oh-My-World/releases)
