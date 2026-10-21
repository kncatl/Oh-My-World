# Oh My World — Formula Writing Guide

## Contents

- 1. Format
- 2. Variables
- 3. Operators
- 4. Built-in Functions
- 5. Expression Examples
- 6. Performance and Rules
- 7. Blocks
- 8. Notes
- 9. Dedicated Servers

This guide is written to ohmyworld/guide.md (guide_en.md in English) at startup.
The bundled version overwrites the same file name on every launch; save your own
edits under a different name.

## 1. Format

Separate layers with semicolons ";". The following forms are supported:

### Single expression

```text
y=<start>..<end>: <expression>
y=<layer>: <expression>             ← single layer, range omitted
y=<start>..: <expression>           ← open end: up to the dimension top
y=..<end>: <expression>             ← open start: from the dimension bottom
y=..: <expression>                  ← the whole dimension
```

ly base of open ranges: y=<start>..: starts at <start>; y=..<end> / y=..:
start at the dimension's minimum y (same as the biome shorthand).

Overlap: for the same y, later layers override earlier ones (open ranges too).
E.g. {y=-64..0: A; y=..: B} ends up all-B (A is fully covered); for a
"base + local override", write the base first: {y=..: B; y=-64..0: A}.

### let block

```text
y=<start>..<end>: {
  let var = <expression>;
  let var = <expression>;
  ...
  <final expression>
}
```

Variables declared with 'let' are scoped to the { } block.
They shadow x/z/ly if names match, and restore on exit.
Tuple bindings are also supported: let (a, b) = multiFunction(...); (section 4).

### shared let (optional)

Write `let var = <expression>;` directly in the formula content (outside any
layer). Every layer and biome line of that dimension can use it. Bindings are
evaluated in declaration order (later ones may use earlier ones); a layer-local
let may shadow a shared variable. Example:

```text
{overworld=
  let sea = 62;
  let hills = seedhash(floordiv(x, 40), floordiv(z, 40), 7);
  y=0..sea: hills < 0.4 ? minecraft:sandstone : minecraft:stone;
  biome: hills < 0.5 ? minecraft:desert : minecraft:plains
}
```

Parameterised shared lets (macros) are expanded at compile time (no runtime
cost) and can be evaluated at different coordinates (slopes, neighbourhoods):

```text
{overworld=
  let h(px, pz) = 64 + noise2(px, pz, 900, 1) * 30;
  let rise(px, pz) = h(px + 1, pz) - h(px, pz);
  y=-64..319: y <= h(x, z) ? (rise(x, z) > 6 ? minecraft:stone : minecraft:grass_block)
                         : minecraft:air
}
```

Macros may call other macros (self reference is caught by the expansion depth
limit); a block-local let shadows macro parameters; a macro name must not
collide with a built-in function (slope, step, ... ; plain lets are unaffected);
declaration order does not matter.

### Comments

```text
// line comments are supported (everything after // on that line is ignored):
```

```text
let base = 64 + fbm2(x, z, 600, 4, 1) * 20; // rolling land
```

Comments are stripped before parsing, so they can sit at the end of any line.

### Cyclic expression

```text
y=<start>..<end>: <count>*[<expr>],<count>*[<expr>],...
```

Within the specified y-range, the comma-separated groups repeat
in cycles until the entire range is filled.

### Example: Cyclic pattern

```text
y=-64..64: 3*[minecraft:bedrock],2*[minecraft:dirt],1*[(x+z)%2==0 ? minecraft:white_concrete : minecraft:gray_concrete]
```

Cycle length = 3+2+1 = 6 layers. Pattern: 3×bedrock, 2×dirt, 1×checker, repeat...

Default preset:

```text
y=-64: minecraft:bedrock;y=-63..64: (x+z)%2==0 ? minecraft:white_concrete : minecraft:gray_concrete
```

### Multi-dimension (optional)

A formula without { } only affects the Overworld. To use formulas in the
Nether / End as well, wrap each dimension's rules in a { } section:

```text
{overworld=
  y=-64: minecraft:bedrock;
  y=-63..64: (x+z)%2==0 ? minecraft:white_concrete : minecraft:gray_concrete
}
{the_nether=
  y=0..5: minecraft:netherrack;
  y=6..120: seedhash(x, z, 1) < 0.5 ? minecraft:basalt : minecraft:blackstone
}
```

- Dimension names: overworld, the_nether, the_end. Shorthands nether / end
  and an optional "minecraft:" prefix are accepted.
- A dimension you do not write keeps vanilla generation: vanilla Nether/End
  biomes, features and structures still generate.
- Alias: {the_end=overworld} reuses the Overworld formula for the End. The
  alias target must be explicitly defined in this formula (chains allowed).
- Whitespace or a semicolon is allowed between sections; layers inside a
  section are still separated by semicolons.
- Each dimension uses its own height range: out-of-range y values are
  clamped (e.g. y=-64..319 only covers 0..255 in the Nether); ly is always
  relative to the layer's declared start.
- Formulas without { } affect only the Overworld.

### Vanilla Overworld

When creating a new world, if the formula has no
overworld section (e.g. only a Nether formula), the new world's Overworld
uses vanilla generation while the Nether/End follow their formulas.
Existing worlds are unaffected (the generator is fixed at creation).
Write an overworld section if you explicitly want a flat/void Overworld.

### Notes

The formula takes over terrain / surface / carvers only: biomes,
features and structures (fortresses, end cities, ores, vegetation, ...)
still generate by the vanilla rules and land inside your formula terrain.
Two suggestions:
- In the End, draw the main island area yourself -- the dragon fight and
  obsidian pillars rely on it.
- No bedrock ceiling is generated unless you draw one (the Nether height
  limit is still 256).

### Structure and Biome Directives (optional)

At the beginning of a dimension's content you may write bracketed
directives (each at most once, any order, all optional; exception: the
overlay-mode [terrain:vanilla] must come first):

```text
{the_nether=
  [structure:none]
  y=0..5: minecraft:netherrack;
  y=6..120: seedhash(x, z, 1) < 0.5 ? minecraft:basalt : minecraft:blackstone
}
{overworld=
  [structure:only=villages,strongholds] [biome:vanilla]
  y=-64: minecraft:bedrock;
  y=-63..64: (x+z)%2==0 ? minecraft:white_concrete : minecraft:gray_concrete
}
```

- [structure:...] controls natural structure generation in that dimension
  (default: all = everything on):
    [structure:all]              everything (default, may be omitted)
    [structure:none]             no structures
    [structure:only=a,b,c]       whitelist: only the listed ones
    [structure:except=a,b,c]     blacklist: everything but the listed ones
  Entries may be "structure set" names (villages, strongholds, mineshafts,
  nether_complexes, end_cities, ...) or individual structure names
  (village_plains, fortress, bastion_remnant, ...); both may be mixed.
  Custom/modded structures use namespace:name (e.g. mymod:tower).

- Structure table (vanilla):
  [Overworld] villages, strongholds, ruined_portals, mineshafts,
    desert_pyramids, jungle_temples, igloos, swamp_huts, shipwrecks,
    buried_treasures, ocean_ruins, ocean_monuments, pillager_outposts,
    woodland_mansions, ancient_cities, trail_ruins, trial_chambers,
    abandoned_camp
  [Nether] nether_complexes (fortress + bastion), nether_fossils,
    ruined_portals (nether variant)
  [End] end_cities

- Structure positions follow the formula terrain (in overlay mode, the
  overlaid terrain). Surface structures (villages, temples, igloos,
  outposts, ...) are skipped when their start position would sit on a water
  surface, so land structures no longer appear on formula oceans; ocean
  structures (shipwrecks, ocean ruins, monuments) and underground structures
  (mineshafts, strongholds, ancient cities, trial chambers, ...) are
  unaffected. Buried structures (e.g. buried treasure) keep the vanilla
  logic and follow the final terrain and soil (thicker soft layers bury the
  chest deeper).

- [biome:...] controls the dimension's biomes (default: unchanged --
  Overworld plains, Nether/End vanilla):
    [biome:vanilla]          vanilla biome distribution for that dimension
                             (coordinate-based, independent of your terrain)
    [biome:minecraft:desert] a single fixed biome for the whole dimension
  With [biome:...]: biome-specific structures (desert pyramids, jungle
  temples, igloos, ...) generate by biome, mob spawning, weather and water
  colours follow the biomes, and decoration features (trees, flowers, ores,
  ...) generate with the new biomes as usual (the flat Overworld base grows
  vanilla vegetation too once its biomes change). Only newly generated
  chunks are affected (existing chunks keep their biomes).

- biome lines -- per-height biome control (3D: the same x/z can use different
  biomes at different y):

```text
{overworld=
  [biome-fallback:3d]
  biome y=-64..30: minecraft:deep_dark;
  biome y=31..60: minecraft:lush_caves;
  biome: minecraft:desert
}
```

```text
biome: <expression>                  covers the whole dimension (shortest)
biome y=<start>..<end>: <expression> covers that height range only
                                     (y=<layer> for a single layer)
```

The expression returns a biome id (write namespace:name, e.g.
minecraft:desert; let / ternaries / all functions work, shared lets apply).
On the same height, later lines override earlier ones. Biomes are stored per
4x4x4 cell (sampled at the cell's origin coordinates).

- Biome lines can also read the vanilla biome distribution directly (it
  never reads formula terrain, so there is no cycle):
    vanilla                   the cell's vanilla biome (identical to
                              [biome:vanilla] per cell)
    biome_at(t, h, c, e, d, w)
                              query the vanilla parameter table; the six float
                              parameters are climate values (temperature/
                              humidity/continentalness/erosion/depth/weirdness,
                              quantized x10000 like vanilla)
  Examples:
    biome: climate(temperature, x, z) > 0.3 ? vanilla : minecraft:desert
    biome: biome_at(0.3, 0.5, 0.2, 0.0, 0.1, 0.4)
  The End has no parameter table (biome_at misses and falls through); superflat
  overworlds work (the table is built from the vanilla overworld preset,
  independent of the generator type); vanilla in a block layer is only valid in
  overlay mode (see below) -- flat-mode block layers report it as an error.

### Overlay mode (terrain:vanilla)

Run formula layers as a post-processing pass over the vanilla noise terrain:
write [terrain:vanilla] at the start of the dimension (it must be the first
directive), then write layers as usual:
    {overworld=[terrain:vanilla] [surface:vanilla+patch]
      y=surf(-30..30): ...}

Semantics:
  - Coordinates no layer covers stay vanilla (equivalent to an implicit first
    layer y=..: vanilla).
  - vanilla = the vanilla block at this position (an order-independent
    snapshot); keep = the result of the layers before it. A result equal to
    the current block is skipped; on the same height later layers override
    earlier ones.
  - The default carver mode is vanilla (with aquifers); [carvers:...] still
    overrides it.
  - Overlay needs a dimension with vanilla noise terrain. When creating a
    Formula Generator world, an overworld section with [terrain:vanilla]
    automatically swaps in the vanilla noise overworld; existing superflat
    worlds (including server_mode superflat worlds) still disable the formula
    for that dimension -- use a normal world preset instead. Overlay biome
    lines cannot read the formula terrain (terrain/surfis/blockis report an
    error).
  - Structure start heights follow the overlaid terrain: villages, ruins etc.
    on lowered or raised terrain are placed against the new surface.

Values available in overlay mode:
  vanilla                   vanilla block value (block layers): leave as is
  vis(block)                is the vanilla block at this position the given block?
  vsolid / vfluid / vair    is the vanilla block here solid / fluid / air?
  sy / sw                   vanilla water-inclusive surface y / liquid surface y
                            (sy = topmost non-air block, vanilla WORLD_SURFACE
                            semantics; sw = topmost fluid, falls back to sy)
  curis(block)              surface rows: is the current (being-patched) block
                            this one?
  sdist(x, z)               horizontal distance to the nearest structure
                            protection box (0 inside; very large when none)

Notes:
  - sd/sdb/wd/slope work in overlay surface rows too, based on the current live
    blocks (overlay writes plus surface rows already applied); curis is overlay-only
    (non-overlay mode reports an error).
  - Layer ranges may be written y=surf(a..b): <expr> -- anchored at each
    column's sy (a and b may be negative; overlay only; cyclic layers are not
    supported).
  - Surface modes (write at the start of the dimension):
    [surface:vanilla]         default: only the vanilla material/surface pass
    [surface:vanilla+patch]   vanilla first, then formula surface rows patch the
                              result (curis finds exposed faces)
    [surface:none]            no vanilla material/surface; surface rows do it all
  - Structure protection: [overlay:protect-structures] is on by default and only
    protects structures with terrain adaptation (villages, outposts, ancient cities,
    strongholds, trial chambers, etc. -- those whose terrain is modified by the
    vanilla terrain adaptation). Structures without adaptation (ocean ruins, shipwrecks, monuments,
    buried treasure, ...) are not protected: their pieces are placed after the
    overlay and their heights follow the overlaid terrain. Inside protected bands
    only solid-on-solid writes are kept; air/water targets are still written, so
    vanilla water or land is not frozen into floating water or terrain pillars.
    Turn it off with [overlay:protect-structures=off] and fade out yourself using
    sdist.
  - The editor preview is approximate (there is no vanilla terrain: vanilla reads
    as air, sy/sw as 0).

- Biome lines can read the formula terrain (computed from the formula layers
  only -- never from already generated blocks -- so it is available during
  biome fill; cyclic layers included):
    terrain(x, z)              surface block y of that column (water counts;
                               an empty column yields world min y - 1)
    surfis(x, z, <block>)      is the surface block the given block? true/false
    blockis(x, z, y, <block>)  is the block at that height the given block?
  Example (biomes by surface material):
    biome: surfis(x, z, minecraft:sand) ? minecraft:desert : minecraft:plains
  The block argument is validated as a block (ternaries and other block-valued
  expressions work). These three functions can only appear in biome lines;
  using them in block layers is an error.

- Block layers can read biomes back:
    biomeis(x, z, y, <biome>)  is the biome at that position the given one?
  Example (surface material by biome):
    y=-63..64: biomeis(x, z, 64, minecraft:desert) ? minecraft:sandstone : minecraft:stone
  The last argument can also be a biome expression (ternary etc.), e.g.
  switching the target by height:
    y=-63..64: biomeis(x, z, 64, y > 0 ? minecraft:desert : minecraft:ocean) ? ...
  Works with [biome:vanilla], [biome:<single biome>] and biome lines alike
  (it reads the biome actually in effect at that position). Block layers
  only; it cannot be combined with biome lines that read the terrain
  (cycle), nor with [biome-fallback:2d] (its reference height needs biomes
  that are not filled yet). The biome expression cannot use terrain queries
  (terrain/surfis/blockis). The editor preview cannot evaluate biomeis
  (it shows the false branch).

- [biome-fallback:...] decides what happens above/below your biome lines
  (default none):
    [biome-fallback:none]  no takeover: biome lines must cover the whole
                           dimension, otherwise the formula fails to parse
    [biome-fallback:3d]    defer to vanilla at the actual height (keeps the
                           vanilla vertical layout: surface bands, cave biomes)
    [biome-fallback:2d]    sample vanilla once at the column's formula terrain
                           surface height, then reuse it for the whole column

Notes: biome lines and [biome:...] cannot be combined (mutually exclusive);
namespaced custom biomes are resolved against the registry at runtime and
when missing the biome source is not applied for that dimension (a log
entry is written). Only newly generated chunks are affected.

- [features:...] controls the dimension's biome decoration features
  (trees, flowers, ores, glowstone, basalt patches and columns, ...;
  only on/off, not per-feature). Either way only your terrain and the
  structures are kept:
    [features:all]   decoration as usual (default, may be omitted): with a
                     [biome:...] rule it follows the new biomes; without
                     one each dimension keeps its natural behaviour
                     (Nether/End keep theirs, the flat Overworld base has
                     no decoration anyway)
    [features:none]  disable all biome decoration features

- [carvers:...] whether vanilla carvers (canyons / classic caves) may carve
  the formula terrain:
    [carvers:none]     default: carvers are cancelled, the formula owns the terrain
    [carvers:vanilla]  allow: vanilla carvers dig canyons/caves into the formula terrain
    [carvers:vanilla-ew] allow, but skip fluid blocks themselves (water and
                       lava) -- no cave/canyon pockets are carved into
                       oceans/rivers/lakes or Nether lava seas; the solid
                       seabed/rock below/around the fluid is still carved.
                       In overlay mode only chunks actually modified by the
                       formula use the dry carver (aquifers disabled);
                       untouched chunks outside the custom region keep vanilla
                       carvers/aquifers. Plain water placed by decoration
                       (springs, lush-cave pools, ...) is removed after the
                       feature pass; formula water stays (ew = except
                       water/lava)
  Works in every dimension:
  - noise-generator dimensions (normal worlds / the Nether & End sections of
    a flat world) carve with their own vanilla settings;
  - flat-world Overworlds too: a "dry" delegate runs the vanilla
    carvers there, so caves stay dry (no aquifer water; lava below y=-54 only).

- Notes:
  - [structure:...] only affects newly generated structures; /locate and
    eyes of ender still use the vanilla theoretical positions.
  - The Overworld biome is plains by default, and many structures need
    their own biome -- combine with [biome:vanilla] to unlock them.
  - Newly created worlds have all structures on by default; in existing
    worlds the structure list is baked into the save, so [structure:...] can
    only remove, not add.
  - Without directives the defaults apply.

## 2. Variables

```text
x    World X coordinate (integer, can be negative)
z    World Z coordinate (integer, can be negative)
ly   Relative y within the layer (0-based, ly = currentY - layerStartY)
y    Absolute y coordinate (= ly + layer start) — prefer y for strata/caves
seed World seed (integer; constant within a world, generally differs across worlds)
```

Use it to make the same formula produce different results in different
worlds. Note: the seed is stored as a 64-bit integer while formulas
compute in double precision (about 53 significant bits); seeds that
differ only in the low bits may yield the same value. Use seedhash()
when you need the full precision.

```text
spawnx Spawn X coordinate (integer; the "spawn chunk centre")
spawnz Spawn Z coordinate (integer)
```

Handy for placing local structures around the spawn point, e.g. a disc
of radius 20:
  (x - spawnx) * (x - spawnx) + (z - spawnz) * (z - spawnz) < 400
The value is the centre of the spawn chunk chosen at world creation:
the game writes that centre first, scans for a safe respawn spot, and
the earliest chunks (the spawn area) are generated during that scan --
so they read the centre. Using the centre everywhere keeps new worlds
seamless; it is usually within about one chunk of the final respawn
point. Every dimension shares the same value.

## 3. Operators

```text
Arithmetic:   +  -  *  /  %
Comparison:   ==  !=  <  >  <=  >=
Logical:      &&  ||  !
Grouping:     ( )
```

Ternary:      condition ? valueA : valueB

Numbers accept integers, decimals and scientific notation (e.g. 1e9, 1.5e-3, 2E+4).

## 4. Built-in Functions

Function call syntax: functionName(arg1, arg2, ...)

### Integer

```text
floordiv(a, b)   Floor division (toward -infinity)
floormod(a, b)   Floor modulo (result always >= 0)
```

### General Math

```text
abs(x)           Absolute value
max(a, b)        Maximum
min(a, b)        Minimum
floor(x)         Floor (round down)
ceil(x)          Ceil (round up)
round(x)         Round to nearest integer
sign(x)          Signum (1, -1, or 0)
sqrt(x)          Square root
pow(a, b)        Power a^b
exp(x)           e^x
log(x)           Natural logarithm ln(x)
log10(x)         Base-10 logarithm
```

### Trigonometric (arguments/returns in radians)

```text
sin(x)           Sine
cos(x)           Cosine
tan(x)           Tangent
asin(x)          Arcsin (-pi/2 ~ pi/2)
acos(x)          Arccos (0 ~ pi)
atan(x)          Arctan (-pi/2 ~ pi/2)
todeg(rad)       Radians to degrees
torad(deg)       Degrees to radians
```

- Trig functions take radians. To use degrees, wrap with torad():

### Example: sin(torad(30)) = 0.5

### Random

```text
rand()                        Random block from ALL registered blocks (excluding air)
rand(b1, b2, ...)             Whitelist — random from the specified blocks
randexcept(b1, b2, ...)       Blacklist — random from all blocks EXCEPT specified ones
seedrand()                    Same as rand(), but mixes in the current world seed
seedrand(b1, b2, ...)         Same as rand(b1, b2, ...), with the world seed mixed in
```

- Note: rand() does NOT use the world seed — the same formula gives the same
  result in every world.
- seedrand() has the same pool/argument rules as rand(), but mixes the **full
  world seed** into the (x, z, y) hash: the same world and coordinates remain
  fully deterministic, while another world gets a different random pattern.
  Use it when the random block palette should differ per world.
- For seed-driven numeric branches, use seedhash() below.

### Seed hashing

```text
seedhash(a, b, ...)   Mixes the world seed with all arguments (zero or more)
                      and returns a decimal in [0,1). Same world + same
                      arguments always give the same value; different worlds
                      generally differ. Ideal for seed-driven noise.
```

- Example: seedhash(x, z, 0) < 0.5 ? minecraft:stone : minecraft:dirt
  (different worlds get different patterns; deterministic within a world)
- For grid noise, floordiv the coordinates first, e.g.
    seedhash(floordiv(x,3), floordiv(z,3), 0).

### Smooth noise (uses the world seed; continuous, for terrain/caves/islands)

```text
noise2(x, z, scale, salt)               2D smooth noise, [-1,1]
noise3(x, y, z, scale, salt)            3D smooth noise, [-1,1]
fbm2(x, z, scale, octaves, salt)        2D fractal sum (octaves 1..8), [-1,1]
fbm3(x, y, z, scale, octaves, salt)     3D fractal sum, [-1,1]
worley2(x, z, scale, salt)              cellular distance to nearest point, [0,1]
worley3(x, y, z, scale, salt)           3D cellular distance, [0,1]
worley2f2(x, z, scale, salt)            distance to the 2nd nearest point (F2), [0,1]
worley2edge(x, z, scale, salt)          F2 - F1: cellular edge lines (~0 at borders), [0,1]
```

- scale = feature size in blocks: larger scale, larger features; change
  "salt" for another pattern.
- Division of labour: seedhash/rand for per-block speckle; noise/fbm/worley
  for smooth terrain, caves and islands.
- worley2f2 / worley2edge are for cellular borders/cracks: edge is ~0 near
  the border lines (e.g. paint cracks where < 0.06); f2 alone is a
  "two-point distance" field. 2D fields only.
- The algorithms are stable: the same formula + seed always generates the
  same world.

### Noise variants (also stable)

```text
fbm2(x, z, scale, octaves, salt, lacunarity, gain)   2D fractal sum with tunable
                                                     lacunarity [1.01,4] and gain
                                                     [0,1]; octaves are offset
                                                     to break lattice alignment
fbma2(x, z, scale, salt, a0, a1, ...)                custom amplitude list (1..8);
                                                     normalised by sum(|a|), [-1,1]
ridged2(x, z, scale, octaves, salt, sharp)           ridged multifractal, [0,1]
billow2(x, z, scale, octaves, salt, sharp)           billowy multifractal, [0,1]
fbm2e(x, z, scale, octaves, salt, k)                 gradient-damped fbm: detail is
                                                     suppressed on steep slopes;
                                                     k=0 equals fbm2, [-1,1]
```

- sharp (1..8) controls how pointy the ridges are; lacunarity/gain tune the
  octave spacing and strength (e.g. 2.2 / 0.55 gives finer detail).

### Splines (custom curve mapping; positions ascending, clamped outside)

```text
spline(v, p0, v0, p1, v1, ...)    piecewise linear: passes through every point
cspline(v, p0, v0, p1, v1, ...)   Catmull-Rom: passes through points, smoother
                                  curve (may slightly overshoot)
```

- Use it to map noise (or any value) through a custom curve, e.g.
  let h = 64 + spline(noise2(x, z, 1200, 1), -1, -30, -0.2, -6, 0.2, 8, 1, 60);
- At least two point pairs (an odd argument count >= 5); positions must be
  ascending (checked ahead of time when all positions are number literals).
  Algorithms are stable.

### Tuple let (multi-return functions: one call, several components)

```text
let (u, v) = warp2(x, z, scale, amp, salt)         2D domain warp: u, v are offsets
let (u, v, w) = warp3(x, y, z, scale, amp, salt)   3D domain warp offsets
let (value, gx, gz) = noise2g(x, z, scale, salt)   smooth noise + analytic gradient
let (f1, f2, cell, px, pz) = worley2c(x, z, scale, salt)
                                                   F1/F2, per-cell random [0,1),
                                                   nearest feature point (blocks)
let (gx, gz) = grad(expr)                          central-difference gradient
                                                   (step 1 block)
```

- Syntax: let (name, name, ...) = multiFunction(...);  the name count must
  equal the component count; multi-return functions may only appear on the
  right side of a tuple let.
- Typical use (warp coordinates before sampling noise):
    let (u, v) = warp2(x, z, 220, 40, 7);
    let base = fbm2(x + u, z + v, 900, 4, 1);
- Components share the usual "independent of height" check: when they do not
  use ly the whole call is evaluated once per column (like a plain let).

### Helpers

```text
clamp(v, lo, hi)             clamp into [lo, hi]
lerp(a, b, t)                linear interpolation a + (b - a) * t
smoothstep(t)                cubic smoothing of a 0..1 input
map(v, a, b, c, d)           linear map from [a,b] to [c,d] (vanilla Mth.map)
waterline(x, z, level)       water surface height of the column: level plus
                             slow noise (feature ~512 blocks, ~±3 blocks,
                             follows the world seed). Use it for "lowland
                             flooding / cave water" (compare it with the
                             terrain height).
atan2(a, b)                  two-argument arctangent (Math.atan2(a, b))
fract(v)                     fractional part (always in [0,1), also for negatives)
step(edge, v)                0 when v < edge, else 1
smootherstep(t)              quintic smoothing of a 0..1 input (gentler)
tanh(v)                      hyperbolic tangent
hypot(a, b)                  sqrt(a² + b²)
bias(v, b) / gain(v, g)      Schlick bias / gain: shape probabilities and curves;
                             with b = g = 0.5 they are the identity
saturate(v)                  clamp into [0,1]
select(cond, a, b)           same as the ternary expression
terrace(v, steps, sharp)     terraces: turn a continuous value into steps of
                             height 1/steps (sharp 1..256 controls the edge)
min(a, b, c, ...)            min/max also fold directly over many arguments
```

### Loop and spatial helpers

```text
sum(k, a, b, expr)           unrolled loop sum: k runs a..b (integer literals).
                             E.g. sum(k, 0, 3, k * x) = 0*x + 1*x + 2*x + 3*x
min/max(k, a, b, expr)       loop form of min/max (4 args, first is a variable name)
shift(expr, dx, dz)          replace x and z inside expr with x+dx and z+dz
                             (x/z inside dx/dz are evaluated at the origin)
slope(expr)                  sqrt(gx²+gz²): central-difference slope (1-block step)
curv(expr)                   Laplacian: sum of second differences (ridges/valleys)
isodist(expr)                |value| / |gradient|: distance estimate to the zero
                             contour (rivers: isodist(noise - threshold) < width)
```

- Loops are expanded at compile time (<=64 iterations per loop; nested total
  <=64); the loop variable shadows an existing binding of the same name.
- Spatial helpers use 1-block central differences; the argument expression is
  re-evaluated at the offsets (outer let bindings keep their centre-column value).
- min/max: the 4-argument loop form applies when the first argument is a variable
  name, a/b are integer literals and the expression references that variable;
  otherwise the four arguments fold (e.g. min(h, 62, 70, y)).

### Grid-sampled caches and smoothing (cheaper 3D density terrain)

```text
cache2d(expr) / cache2d(expr, step)              world-aligned grid, bilinear
                                                 interpolation (step 1..16, default 4)
cache3d(expr) / cache3d(expr, sx, sy, sz)        trilinear interpolation over a 3D
                                                 grid (default 4, 8, 4; corners use
                                                 the absolute y)
blur2(expr, r) / blur2(expr, r, step)            (2r+1)² box convolution at grid
                                                 points (r 1..4; step default 4)
```

- Value = expr evaluated at the grid corners, interpolated; the grid is aligned to
  world coordinates (seamless across chunks) and each corner is computed only once
  (per-thread caches).
- blur2: each grid point's value is the average of expr over the (2r+1)² grid
  points around it (spacing = step); the query point interpolates bilinearly
  between grid points. Use it to smooth 0/1 masks or river distances into soft
  transitions (e.g. blur2(riverMask, 3, 8) gives a ~24-block smoothing band).
- The smoothing scale is r × step blocks: detail smaller than that is averaged
  out; when the result is thresholded (say 0.4/0.6) the transition band is of
  the same order (r=3, step=8 ≈ 15 blocks; r=4, step=16 ≈ 60 blocks). The
  default r=2, step=4 only smooths detail within ~8 blocks.
- Macros are expanded before the self-containment check: cache/blur2/rivernet
  expressions may call macros (the expansion must still be self-contained).
- The expression must be self-contained: only built-in values (x/y/z/ly/seed/
  spawnx/spawnz), no let bindings, and no terrain/surfis/blockis/biomeis/rand.
  cache2d and blur2 must not use ly or y; cache3d must not use ly (y is allowed).
- Use it to down-sample expensive fields (3D density, large-scale noise).

### Surface channel (surface rows: dress solid terrain by depth/water/slope)

```text
surface y=<a>..<b>: <expr>       apply the rule within that height range
surface: <expr>                  whole-dimension shorthand
surface[maxdepth=N] y=..: ...    only evaluate blocks with sd <= N (1..64, default 8)
```

- Runs after the formula terrain is laid out and before it is written to the chunk
  (before carvers and features, same as vanilla).
- The expression returns a block or keep (leave the current block); for the same y,
  later rows override earlier ones.
- Variables (only valid in surface rows):
    sd     consecutive solid blocks above (top face = 0)
    sdb    consecutive solid blocks below (cave ceilings/floors)
    wd     -1 when dry; otherwise the number of fluid blocks above (>= 1)
    slope  max absolute height difference to the four neighbour columns (1 block apart)
  (bare `slope` is this column value; slope(expr) is the function -- the context
  tells them apart)
- surface rows can also read biomes (biomeis(x, z, y, <biome>)) -- they read the
  biome in effect.
- solid = not air and not fluid; neighbour tops come from the pre-surface terrain
  (chunk-independent, no ordering effects).
- The editor preview and structure placement use the pre-surface terrain
  (surface rows only apply during chunk generation).
- Example (sand under water + stone on steep slopes + keep the rest):
    surface wd >= 1 && sd == 0 ? minecraft:sand : keep;
    surface slope > 2 && sd <= 1 ? minecraft:stone : keep

### River network (rivernet: non-local rivers; combine with min)

```text
let (dist, wd, surf, order) = rivernet(cs, salt)         default height field
let (...) = rivernet(coarse, cs, salt)                   custom field (column expr)
Optional tail parameters: (..., w0, wk) half-width base/flow slope; (..., w0, wk,
warp) also the meander amplitude. Full forms: rivernet(cs, salt[, w0, wk[, warp]])
and rivernet(coarse, cs, salt[, w0, wk[, warp]]).
```

- Jittered grid nodes + strictly descending downstream + depth-3 flow
  accumulation → half-width = w0 + wk·sqrt(flow) (defaults w0=3, wk=1).
- Returns: dist = distance to the nearest river centreline; wd = local half
  width; surf = water surface (node elevation interpolated along the segment
  minus 3); order = accumulated upstream flow.
- cs in 64..512 (integer literal; larger values give a sparser network with
  fewer tributaries); salt/w0/wk/warp must be number literals
  (w0/wk/warp >= 0); nodes with elevation <= 62 are terminals (they flow into
  the sea). When coarse is omitted an internal field (62 ± 28 multifractal
  noise) is used. warp = meander amplitude in blocks (default 0.12×cs;
  0 gives straight polylines).
- Appearance: channels are drawn as "drainage trees" -- one tree per basin
  (trees meet at confluences) separated by watersheds (no river between
  them); this is the designed shape, not a defect. Density ≈ cs. For
  longer, network-like rivers, give coarse a large-scale slope (e.g.
  76 - sqrt(x*x+z*z)*0.015) so water runs from the highlands down to the sea.
- coarse must be self-contained (built-ins only, no ly/y, no view/random
  functions).
- With no river nearby: dist = 1e9 and surf = 1e9 — min(base, ...) then keeps
  the normal terrain.
- Example (river + bed + water; the coarse field matches base, so the water
  surface never towers over the terrain):
    let base = 60 + fbm2(x, z, 600, 4, 1) * 20;
    let (d, w, s, o) = rivernet(60 + fbm2(x, z, 600, 4, 1) * 20, 192, 7);
    let surf = min(s, base + 2);
    let bed = min(base, surf - 4 + max(d - w, 0) * 0.7);
    y=..: y <= bed ? (d < w && y > bed - 3 ? minecraft:sand : minecraft:stone)
         : (d < w && y <= surf ? minecraft:water : minecraft:air)
- Tuning: w0 = minimum width, wk = how fast confluences widen the river,
  warp = how much the channel meanders. The two fbm2 calls must match (same
  height field), otherwise the water and the terrain come from different
  sources and tall water columns appear.

### Shared climate (available in generation and the editor preview)

```text
climate(field, x, z)         vanilla climate value (~[-1,1]; the 2D form samples at y=63)
climate(field, x, y, z)      any height (also supports depth)
peaks(w)                     vanilla peaks-and-valleys fold -3·(|(|w| - 2/3)| - 1/3)
```

- Fields: temperature / humidity / continentalness / erosion / weirdness / depth
  (field names are keywords, not variables; values are quantized in steps of 1e-4,
  sampled at vanilla's 4-block resolution: identical within a 4×4×4 cell).
- Used directly for terrain this gives 4-block steps — wrap it in cache2d for
  a bilinear smoothing, e.g. cache2d(climate(temperature, x, z), 4).
- Values come from the dimension's vanilla climate sampler (same as the F3
  NoiseRouter line; the nether/end use their own settings). One set of parameters
  can drive terrain, biomes and rivers together.
- The editor preview builds the climate locally from the creation screen's seed
  (matching the final world); contexts without a climate view return 0.

### Vanilla density functions / noises / surface height (generation and preview)

```text
df(id, x, y, z)              evaluate a registered density function
                             (e.g. minecraft:overworld/ridges; single-point)
noise(id, x, y, z[, xzScale[, yScale]])
                             evaluate a registered noise (e.g. minecraft:temperature);
                             xzScale applies to x/z, yScale to y only (default 1)
vheight(x, z)                estimated vanilla surface height (finalDensity scan + bisect)
```

    - Ids need a namespace (datapack entries work too); missing entries return 0 and
      are logged once per id.
    - df/noise single-point evaluation has no vanilla caching -- calling them per block
      is very slow. Always wrap them in cache2d/cache3d, e.g. at a fixed height:
      cache2d(df(minecraft:overworld/ridges, x, 63, z), 4) (cache2d cannot use the
      variable y; use cache3d for a height-dependent field).
      vheight has its own column cache (each column is scanned once), so per-block
      calls are fine.
    - vheight describes the *vanilla noise terrain* (on superflat worlds it follows the
      dimension's noise settings), independent of formula/overlay terrain; a fully
      non-solid column returns the dimension's bottom y.
    - vheight costs a few dozen finalDensity samples per column (the column cache only
      ensures one scan per column) -- calling it for every column slows generation
      noticeably. Use it only where cross-column decisions need it, or restrict the
      call sites with a lattice check (e.g. floormod(x, 8) == 0).
    - Values follow the dimension's noise settings and seed (same as the F3 entry).

## 5. Expression Examples

### Example: Uniform layer (same block everywhere)

```text
y=-64: minecraft:bedrock
```

### Example: 1x1 checkerboard (alternating blocks)

```text
y=-64..64: (x+z)%2==0 ? minecraft:white_concrete : minecraft:gray_concrete
```

### Example: 3x3 grid (large cells, no center swap)

```text
y=-64..64: (floordiv(x,3)+floordiv(z,3))%2==0 ? minecraft:white_concrete : minecraft:gray_concrete
```

### Example: 3x3 grid + center swap

```text
y=-64..64: (floordiv(x,3)+floordiv(z,3))%2==0
  ? (floormod(x,3)==1 && floormod(z,3)==1 ? minecraft:gray_concrete : minecraft:white_concrete)
  : (floormod(x,3)==1 && floormod(z,3)==1 ? minecraft:white_concrete : minecraft:gray_concrete)
```

### Example: Horizontal stripes (every 4 blocks along X)

```text
y=-64..64: floordiv(x,4)%2==0 ? minecraft:white_concrete : minecraft:gray_concrete
```

### Example: Circle (radius 20)

```text
y=-64..64: (x*x+z*z)<=400 ? minecraft:white_concrete : minecraft:gray_concrete
```

### Example: Diamond region (absolute values)

```text
y=-64..64: (abs(x)+abs(z))<=20 ? minecraft:white_concrete : minecraft:gray_concrete
```

### Example: Sine wave stripes

```text
y=-64..64: sin(torad(x*10))>0 ? minecraft:white_concrete : minecraft:gray_concrete
```

### Example: Cyclic: 3 bedrock + 2 dirt + 1 checker (6-layer cycle)

```text
y=-64..64: 3*[minecraft:bedrock],2*[minecraft:dirt],1*[(x+z)%2==0 ? minecraft:white_concrete : minecraft:gray_concrete]
```

### Example: Blacklist random (exclude stone, dirt, grass)

```text
y=-64..64: randexcept(minecraft:stone, minecraft:dirt, minecraft:grass_block)
```

### Example: Volumetric model (using ly with air for hollow structures)

```text
y=-64..64: (x+z)%2==0 && ly<10 ? minecraft:white_concrete : minecraft:air
```

10-block-tall pillars only on checkerboard white positions, air elsewhere.

### Example: Seeded block random (different worlds, different pattern)

```text
y=-64..64: seedrand(minecraft:stone, minecraft:cobblestone, minecraft:mossy_cobblestone)
```

### Example: Seed-driven pattern (same formula, different worlds differ)

```text
y=-64..64: seedhash(x, z, 0) < 0.5 ? minecraft:white_concrete : minecraft:gray_concrete
```

For grid noise, floordiv the coordinates first, e.g.
seedhash(floordiv(x,3), floordiv(z,3), 0) < 0.5 ? ... : ...

### Example: Multi-dimension (Overworld checker + Nether formula + End alias)

```text
{overworld=y=-64: minecraft:bedrock;y=-63..64: (x+z)%2==0 ? minecraft:white_concrete : minecraft:gray_concrete}
{the_nether=y=0..40: seedhash(x, z, 7) < 0.5 ? minecraft:basalt : minecraft:blackstone}
{the_end=overworld}
```

The End has no rules of its own: it reuses the Overworld formula via an
alias (each dimension still uses its own height range).

### Example: let block — avoid recomputing sub-expressions for complex formulas

```text
y=-64..319: {
  let cellX = floordiv(x, 192);
  let cellZ = floordiv(z, 192);
  let cellSeed = cellX*37.1 + cellZ*91.7;
  let offX = sin(cellSeed)*40;
  let offZ = cos(cellSeed*1.3)*40;
  let dx = x - (cellX*192 + 96 + offX);
  let dz = z - (cellZ*192 + 96 + offZ);
  dx*dx + dz*dz < 400 ? minecraft:white_concrete : minecraft:air
}
```

Extract repeated sub-expressions into named variables for clarity.

### Example: Tuple let — receive several return values at once (domain warping)

```text
y=-64..319: {
  let (u, v) = warp2(x, z, 240, 45, 7);
  let h = 64 + fbm2(x + u, z + v, 900, 4, 1) * 26;
  y <= h ? minecraft:stone : minecraft:air
}
```

warp2 returns two offsets; adding them to the coordinates before sampling
noise "creases" the coordinates, giving more natural terrain edges.
Multi-return functions can only be received like this:
let (name, name, ...) = multiFunction(...).

### Example: Deepslate-style strata transition (hash noise x height ramp, like vanilla)

```text
y=-64..64: seedhash(x, y, z, 11) < clamp(map(y, 0, 8, 1, 0), 0, 1)
  ? minecraft:deepslate : minecraft:stone
```

Same principle as vanilla deepslate: per-block hash noise compared to a
probability that is 1 at y<=0, 0 at y>=8 and linear in between, so the
band interleaves block by block instead of forming a hard line. Tuning:
the 0 and 8 inside map() are the bottom/top of the transition band; change
the last hash argument (11) for another random pattern; the same shape
makes any strata (weathered/mortar layers) -- just swap the block.

### Example: Around the world spawn (spawnx / spawnz)

```text
y=-64..319: {
  let dx = x - spawnx;
  let dz = z - spawnz;
  dx*dx + dz*dz < 400 ? minecraft:white_concrete : minecraft:air
}
```

A disc of radius 20 centred on the world spawn. For spawn-centred
landmarks (monuments, tower bases) use spawnx/spawnz instead of copying
coordinates by hand.

## 6. Performance and Rules

- Noise/cellular functions are a stable contract: the same formula and seed
  generate the same world.
- Put strata and large-scale noise in shared lets (the ly-independent part is
  cached per column automatically);
- Use seedhash/rand for per-block random patches; use noise/fbm/worley for
  continuous relief;
- When carving holes (air branches) into terrain, keep an "original block"
  fallback at the end -- a whole-column air branch digs the terrain away;
- Put local heavy computation inside ternary / && / || branches (the engine
  is lazy and short-circuiting) with a cheap bounding-box/distance guard in
  front -- positions that do not need the value never compute it;
- Shared lets are lazy: bindings that are never used are not evaluated, and a
  binding used only inside a branch runs only on columns that reach that branch.
  The ly-independent part is cached per column; parts that depend on height only
  through 4x4x4-quantised data (climate etc.) are cached per 4x4x4 cell;
- Layer order (later overrides earlier) does not change cost; the layer's
  y range and branch structure do;
- The editor preview renders with the "current seed": opening the editor from
  the creation screen reads the seed box (an empty box uses the screen's
  current random seed, which is also used at creation); change the seed and
  reopen the editor to re-sync. Inside a world the preview matches the real
  seed. rand() does not depend on the seed.

## 7. Blocks

Use standard Minecraft resource IDs:

```text
minecraft:white_concrete   minecraft:gray_concrete   minecraft:bedrock
minecraft:dirt             minecraft:grass_block      minecraft:stone
minecraft:glass            minecraft:cobblestone       minecraft:oak_planks
```

Supported concrete colors (hardcoded, no registry needed):
  white_concrete, gray_concrete, black_concrete, red_concrete,
  blue_concrete, yellow_concrete, green_concrete, orange_concrete,
  purple_concrete, light_gray_concrete, light_blue_concrete,
  magenta_concrete, lime_concrete, pink_concrete, cyan_concrete,
  brown_concrete

Other blocks are resolved via BuiltInRegistries.BLOCK.

## 8. Notes

- Semicolons ";" and block IDs must be typed in English mode.
- Use parentheses around nested ternaries; for grid patterns (/ or %) use
  floordiv/floormod to keep negative coordinates well-defined.
- ly is layer-relative; the built-in "y" is the absolute y (= ly + layer start).
  Prefer y for strata/cave formulas.
- Set "Generate Structures" to OFF when the top layer of your pattern is
  not a flat, continuous surface.

## 9. Dedicated Servers

Dedicated servers have no world-creation UI, so the formula cannot be written
from the editor. Set server_mode: true in config/ohmyworld.json instead;
the formula is then applied to every dimension that has a formula section (both
superflat and noise-generator worlds; [terrain:vanilla] overlay mode requires a
noise generator). With server_mode, config formula edits take effect in about
5 seconds without restarting the server (newly generated chunks only).

Two ways to provide the formula (pick one):

```text
"formula": "..."         Inline. Newlines must be written as \n and quotes
                         as \" inside the JSON string; pasting a complex
                         formula directly easily corrupts the config.
"formula_file": "path"   Recommended: read the formula from a .txt file,
                         preserving newlines/quotes/comments. Absolute paths
                         are used as-is. With server_mode, relative paths are
                         resolved in this order: ohmyworld/ → server root →
                         config/ (e.g. ohmyworld/formula.txt, formula.txt or
                         config/formula.txt); outside server_mode the order is
                         still server root → config/. formula_file wins over
                         formula when non-empty; edits reload in about 5 s.
```

Outside server_mode, the formula / formula_file fields in the config are not
read at all: a world without a marker generates vanilla, and a world with a
marker follows that marker. The config fields only apply with server_mode.

If the config or formula file cannot be parsed, server_mode does not fall back
to the default formula (the world stays vanilla) and the log shows the path and
reason; hot reload retries after the file is fixed.

The server_mode formula also accepts the sectioned syntax: each dimension
that has a section is taken over, dimensions without one stay vanilla. The
server-side Overworld generator is decided by level-type: for "vanilla
Overworld + formula-driven Nether/End", set level-type to normal (the
default) and write only the the_nether / the_end sections.

- Adding or removing a dimension section on a running server requires a
  server restart (leave and re-enter the world in singleplayer) before it
  affects the already-created generators; editing the formula text inside
  the sections still takes effect in about 5 seconds.
- Complex formulas (especially [terrain:vanilla] overlay plus many surface
  rows) make portal arrival and first visits to a dimension slower: the
  server has to generate the chunks around the destination. Revisited areas
  reuse chunks and are much faster. Pre-generate common destinations with
  /forceload, lower view distance, use cache2d / cache3d, and avoid per-block
  heavy noise and excess surface rows.
- For troubleshooting, set "debug_logs": true in config/ohmyworld.json: the
  log then shows per-dimension takeover decisions, formula reads and
  writes, and the overworld replacement decision (default false).
