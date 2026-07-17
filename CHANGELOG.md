# Changelog

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
