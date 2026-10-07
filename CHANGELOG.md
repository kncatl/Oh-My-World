# Changelog

## v1.2.4-beta

> Beta release — please report any issue on GitHub.

### Added
- **Biome control by formula** — three linked features:
  - **Biome lines** in dimension sections: `biome: <expression>` covers the whole dimension and `biome y=a..b: <expression>` a height range, so the same x/z can use different biomes at different heights (3D). Biome ids are written `namespace:name`, later lines override earlier ones, and `let` blocks, ternaries and every function work.
  - **`[biome-fallback:none|2d|3d]`** decides what happens where no biome line matches: `none` (default) requires the lines to cover the whole dimension (parsing otherwise reports the first uncovered y), `3d` defers to the vanilla distribution at the actual height, and `2d` samples vanilla once at the formula terrain's surface height and reuses it for the whole column.
  - **Biomes and terrain can read each other**: shared section-level `let` bindings; biome lines can query the formula terrain (`terrain(x, z)`, `surfis(x, z, <block>)`, `blockis(x, z, y, <block>)` — computed from the formula, so they are available while biomes are being filled); block layers can query biomes (`biomeis(x, z, y, <biome>)`) and that works with `[biome:vanilla]`, `[biome:<single biome>]` and biome lines alike.
- The in-game guide documents all of the above.

### Compatibility
- Formulas without biome lines keep their exact previous behaviour; biome lines only affect newly generated chunks. Worlds, formulas and saved files are unaffected.
- Combination rules (parse-time errors): biome lines cannot be combined with `[biome:...]`; terrain queries are biome-line only and `biomeis` is block-layer only, and the two cannot be mixed (they would form a cycle); `[biome-fallback:2d]` cannot be combined with `biomeis`; the editor preview cannot evaluate `biomeis` (it shows the non-matching branch).

## v1.2.3-beta.2

> Beta release — please report any issue on GitHub.

### Fixed
- Fabric builds no longer pin an exact Fabric API version: the metadata for 1.21.1 / 1.21.11 / 26.3 required the exact Fabric API build they were compiled against (e.g. `0.161.0+26.3`), so the mod refused to load once a newer Fabric API was released (`0.162.0+26.3`). Every node now declares an open lower bound, and future Fabric API releases on those lines will keep working.

### Compatibility
- Only the Fabric dependency metadata changed; all other files are identical to v1.2.3-beta.1. Worlds, formulas and saved files are unaffected.

## v1.2.3-beta.1

> Beta release — please report any issue on GitHub.

### Added
- **Syntax highlighting** in the formula editor: the field is now a custom-drawn editor that colours directives/keywords, numbers, block and dimension ids, variables (`x`, `z`, `ly`, `seed` and `let` bindings), function names and operators. Editing behaviour is unchanged (selection, clipboard, word jumps, double-click word select, soft wrapping, in-field scrolling).
- **Error marks**: validation errors are mapped back to the text and highlighted with a translucent red background plus a red underline (the field border also turns red while errors exist); whole-formula problems carry no marks.
- **Preview interaction**: drag the preview to pan, scroll or use -/+/Reset to zoom (1/2/4/8/16 blocks per cell), with X/Z coordinate rulers outside the frame; when zoomed past 1x the preview samples at double density for finer detail.

### Fixed
- 26.x (SDL input backend): the preview map could not be dragged and Ctrl+Enter did nothing; typing into the formula editor produced nothing (text input is focus-driven since 26.1, and 26.3 additionally expects a capture declaration - the editor now announces itself like the vanilla text fields).
- 1.21.1: unknown block names were silently accepted (that version's block registry falls back to air for unknown ids instead of returning null), so a wrong block name produced neither an error message nor an error mark.
- The formula editor scrollbar is now draggable: drag the thumb, or click the track to jump there.

### Compatibility
- Editor/UI only: formula syntax, parsing, world generation and save formats are unchanged; existing worlds, formulas and saved files keep working.
- Server-side generation is unchanged: servers may stay on an earlier version, but upgrading both sides together is recommended.

## v1.2.2-beta.1

> Beta release — please report any issue on GitHub.

### Changed
- **The formula editor got a full makeover** (client-side UI only; formula syntax and world generation are unchanged):
  - New card layout with clear sections, grouped buttons and a live status area (parse summary such as "Parsed 2 dimension(s) - Overworld: 12 layers", error list, save/load feedback).
  - **The formula field is a real multi-line editor**: soft wrapping, proper newline display, mouse drag-selection and internal scrolling.
  - **Live top-down preview**: samples the top block of each column and colours it by its map colour; refreshes about 0.5s after typing stops, and a "Switch dimension" button previews the Nether/End.
  - **Examples button**: four built-in formulas (checkerboard / 3x3 grid / cyclic layers / multi-dimension + seedhash) fill in with one click.
  - **Fullscreen editing**: expand long formulas into a full-window editor (Ctrl+Enter to finish, Esc to cancel).
  - **Ctrl+Enter** applies the formula.
  - **Open Guide button**: opens the bundled formula guide (per language), falling back to the ohmyworld folder.
  - **Saved-formula management**: entries show their last-modified time and support rename/delete (with confirmation).
- Fixed: formulas with newlines keep their line breaks when the editor is reopened; the name-field placeholder was too dark on 1.21.1; widget misalignment and a crash when the window was resized or the screen reopened.
- Fixed: Fabric 1.21.1-1.21.5 (<1.21.11) crashed when opening the formula editor (the multi-line compat code used mojmap reflection; it now uses compile-time calls with a single-line fallback so the screen always opens).
- Fixed: startup crash on old Fabric Loaders (0.18.x, bundled MixinExtras 0.5.0) - the injectors now use `@WrapOperation`, avoiding the old MixinExtras issue with array-shaped `@Redirect` annotations; updating the loader is still recommended.

### Compatibility
- UI-only change: formula syntax, parsing, world generation and save formats are unchanged; existing worlds, formulas and saved files keep working.
- Server-side generation is unchanged: servers may stay on v1.2.1-beta.1, but upgrading both sides together is recommended.

## v1.2.1-beta.1

> Beta release — please report any issue on GitHub.

### Added
- **Dimension directives**: each dimension's formula content may start with optional `[structure:...]`, `[biome:...]` and `[features:...]` directives (any order, all optional; omitting them keeps the old behaviour exactly).
- **Structure control `[structure:all|none|only=a,b|except=a,b]`**: per-dimension on/off/whitelist/blacklist for natural structures. Entries may be structure set names (`villages`, `strongholds`, `mineshafts`, `nether_complexes`, `end_cities`, ...) or individual structure names (`village_plains`, `fortress`, ...), mixed freely; custom/modded structures use `namespace:name`. Worlds newly created with this version have all structures on by default (the preset's structure override list is gone); for older worlds the list is baked into the save, so `[structure:...]` can only remove, not add. `/locate` and eyes of ender still use the vanilla theoretical positions.
- **Biome control `[biome:vanilla|<biome id>]`**: `vanilla` uses that dimension's vanilla biome distribution (the Nether/End already do); `[biome:minecraft:desert]` etc. fixes the whole dimension to one biome. Biomes are distributed by coordinate, independent of your terrain; biome-specific structures, mob spawning, weather and water colour follow them, and decoration features (trees, flowers, ores, ...) generate with the new biomes as usual (the flat Overworld base grows vanilla vegetation too once its biomes change); only newly generated chunks are affected (existing chunks keep their biomes).
- **Decoration feature switch `[features:all|none]`**: per-dimension on/off for biome decoration features (trees, flowers, ores, glowstone, basalt patches and columns, ...; on/off only, not per-feature). Default `all`: with a `[biome:...]` rule decoration follows the new biomes; without one each dimension keeps its natural behaviour (Nether/End keep theirs, the flat Overworld base has no decoration anyway). `none` disables all decoration.

### Compatibility
- Formulas without directives behave exactly as before; `rand`/`randexcept` are unchanged.
- Directives are stored together with the formula in the world marker and in `config/ohmyworld.json`; both the server_mode path and the create-world path support them.
- Biome/feature swapping happens at runtime and is not written to the save — on existing worlds, enable server_mode and put a directive-carrying formula in the config.

## v1.2.0-beta.1

> Beta release — please report any issue on GitHub.

### Added
- **Multi-dimension formulas**: write per-dimension rules in `{overworld=...}{the_nether=...}{the_end=...}` sections. Dimensions without a section keep vanilla generation; `{the_end=overworld}` reuses another dimension's formula (aliases can be chained). Shorthands (`nether`, `end`) and an optional `minecraft:` prefix are accepted.
- **Vanilla Overworld option**: when a formula has no `overworld` section, newly created worlds use the vanilla Overworld generator while the Nether/End follow their formulas. The editor shows a hint for this case; existing worlds are unaffected.
- **`seed` variable** and **`seedhash(...)` function**: the world seed is available to formulas, and `seedhash` mixes the full 64-bit seed with its arguments (returns `[0,1)`), so the same formula can differ between worlds. The mixing algorithm is frozen (documented as a compatibility contract).
- Strongholds now generate in formula worlds (alongside villages), matching current vanilla superflat — the End portal is reachable again.
- New `debug_logs` option in `config/ohmyworld.json` (default `false`) for diagnostic logging.

### Fixed
- **Worlds created with the Formula Generator preset had no Nether/End at all** (trying to use a portal did nothing). The preset now defines both dimensions like the vanilla presets do. Worlds created before this version cannot gain them retroactively — create a new world to get them.
- The "vanilla Overworld" replacement never ran on the create-world screen (the injection point was only used by the quick-create path); it now hooks the actual creation step.

### Compatibility
- Formulas without `{}` behave exactly as before (they only affect the Overworld).
- `rand`/`randexcept` are unchanged.
- Worlds created with this version store the sectioned syntax in their marker; opening them with an older mod version disables the pattern (documented). Worlds created before this version keep working — note they will still lack the Nether/End.
- The light values saved in a chunk are light-engine state and can differ slightly between any two runs.

## v1.1.7

### Added
- Minecraft **1.21.1–1.21.11** and **26.1–26.3** are now supported on both NeoForge and Fabric (14 builds in total; previously only 1.21.1 and 1.21.11 were published). Every declared version range is covered by a build that was verified against a real server and a real client of that version.
- 26.x requires **Java 25**; the 1.21.x line continues to use **Java 21**.

### Compatibility
- Version boundaries that needed code changes while widening support: 1.21.2, 1.21.5, 1.21.6, 1.21.11 (screen drawing API renames and a return-type change), 26.2 (`Minecraft.setScreen` removal, block colour collections) and 26.3 (terrain pipeline rework).
- No formula syntax or behaviour changes: formula files saved with v1.1.6 and worlds created with it keep working unchanged.

## v1.1.6

### Changed
- Function calls are resolved to integer ids when a formula is parsed, instead of comparing and hashing the function name on every call. Complex formulas evaluate roughly 15% faster per block.
- Measured on the same 37-binding formula as v1.1.5: spawn-area preparation dropped from 11.4 s to 9.7 s.

### Compatibility
- No formula syntax or behaviour changes. Verified against the v1.1.5 build by generating a world from the same seed and comparing chunk by chunk: 841/841 chunks are byte-identical for heightmaps, block palettes, packed block data and biomes.

## v1.1.5

### Changed
- World generation is faster again for formulas that mix height-dependent and height-independent values. A `let` binding whose value does not depend on height is now evaluated once per column and reused for the whole vertical range, instead of being recomputed for every block. Only the height-dependent part of the formula is still evaluated per block.
- Measured on the same 37-binding formula as v1.1.4: per-block expression evaluation is down from about 1.5 µs to about 1.1 µs, and spawn-area preparation from 17.4 s to 11.4 s (1.5x).

### Compatibility
- No formula syntax or behaviour changes. Verified by generating two worlds from the same seed and comparing 841 chunks chunk by chunk: heightmaps, block palettes, packed block data and biomes are byte-identical. (The light values saved in a chunk are light-engine state rather than world generation output, and can differ slightly between any two runs.)

## v1.1.4

### Changed
- World generation is substantially faster for complex formulas. Four changes, none of which alter the terrain that is produced:
  - `let` bindings are compiled to array slots when the formula is parsed, instead of being looked up in a hash map for every single block.
  - Function calls no longer allocate an argument list per call.
  - Arithmetic on numeric subexpressions is carried out as primitive `double` values, so intermediate results are no longer boxed.
  - Blocks that resolve to air are no longer written into the chunk. A freshly generated chunk is already air there, and for air written bottom-up the accompanying heightmap updates are no-ops.
- Measured on a formula with 37 `let` bindings spanning 383 vertical levels: per-block expression evaluation dropped from roughly 3.3 µs to 1.5 µs, and spawn-area preparation from 45.0 s to 17.5 s (2.6x).

### Compatibility
- No formula syntax or behaviour changes. Verified by generating two worlds from the same seed with the old and new code and comparing them chunk by chunk: heightmaps, block palettes and packed block data are byte-identical.

## v1.1.3

### Changed
- World generation is much faster for layers that do not depend on height. These are now evaluated once per column and reused for the entire vertical range, instead of being recomputed for every single block. Measured reduction for such layers: roughly 50x to 90x fewer expression evaluations.
- Block literals are resolved once instead of on every evaluation, removing tens of thousands of registry-cache lookups per chunk.

### Compatibility
- No formula syntax or behaviour changes. Every formula produces exactly the same terrain as before.

## v1.1.2

### Fixed
- Fixed newer NeoForge builds refusing to load the mod. The `neoforge` dependency was declared as an exact version (`[21.1.234]`) rather than a minimum, so any loader above 21.1.234 rejected it with a version error and users had to downgrade.
- The dependency is now declared as `[21.1.234,)`, and the mod ships built against 21.1.251.

### Compatibility
- Loads on NeoForge 21.1.234 and every later 21.1.x build. No API changes were needed: the NeoForge and FancyModLoader classes this mod uses are unchanged across that range.

### Changed
- Dropped the explicit `bus = EventBusSubscriber.Bus.MOD` argument. FancyModLoader already routes each subscriber by its event's type (`IModBusEvent` → mod bus, otherwise the game bus), so the argument had no effect. It is deprecated for removal, and removing it now means no code change is needed when it is eventually deleted.

## v1.1.1

### Fixed
- Fixed client-only Fabric mixins being loaded on dedicated servers.
- Fixed NeoForge preset-editor registration and repeated world-creation state.
- Fixed nested `let` scope evaluation and rejected non-block layer results.
- Fixed formula failures leaving partial chunks and incorrect base heights.

### Changed
- Formula snapshots are bound to individual flat generators.
- Added high safety ceilings for input size and parser nesting without imposing a generation budget.

## v1.1.0

### New
- **Load saved formulas**: A "Load Formula" button has been added to the formula editor. Select from previously saved `.txt` files to instantly load them into the editor.
- **Real-time error feedback**: Formula parse errors are now displayed directly in the editor (below all buttons), with line numbers and error messages. No more silent failures.

### Fixed
- **Multiplayer compatibility**: Clients without the mod can now join a host's world that uses formula-generated terrain. The mod no longer registers a custom chunk generator type — everything is handled via mixin injection into the vanilla flat level source.
- **Short-circuit evaluation**: `&&` and `||` operators now properly short-circuit (right side is no longer evaluated when the result is determined by the left side).
- **Nested let blocks**: Variables declared in an outer `let` block can now be referenced inside nested `let` blocks.
- **Cyclic layer `ly` variable**: `ly` in cyclic layers now correctly represents the layer-relative Y (consistent with the guide documentation), instead of the per-entry offset.
- **Unknown function/character errors**: Writing an unsupported function name or invalid character now produces a clear error message instead of silently generating an air-only world.

### Changed
- Removed references to `smooth` and `rng` functions from documentation (these were never implemented).
- Cleaned up debug logging.
- Standardized the mod's group ID to match the actual package name.

---

## v1.0.3

- Initial public release.
