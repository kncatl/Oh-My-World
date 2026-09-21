# Changelog

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
