<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# IndentScope Changelog

## [Unreleased]

### Added

- Lifecycle regression tests for controller/service disposal and concurrent editor registration.
- Opt-in guides for scopes that start at column 0, such as top-level DTOs and Python modules. The new **Show the guide
  for scopes that start at column 0** setting is off by default and can be enabled in **Settings → Tools →
  IndentScope**.
- CI matrix on Ubuntu, macOS and Windows, Gradle wrapper validation, ktlint and Kover coverage reports.
- Compatibility verification against IntelliJ IDEA 2025.3.6.1, 2026.1.5 and Android Studio 2026.2.1.8.
- Demo GIF and screenshots in the README, plus a full Spanish README (`README.es.md`).
- Separate light and dark plugin icons (`pluginIcon.svg` and `pluginIcon_dark.svg`).
- Bilingual plugin description with documentation and issue-tracker links.
- Known limitations, support links and a per-version Marketplace checklist.

### Changed

- Vendor and organization name updated to **NeoPlus** for future releases.
- The build declares JDK 21 explicitly with `jvmToolchain(21)` and enforces ktlint formatting.
- Status-bar source file renamed to `IndentScopeStatusBarWidgetFactory.kt` to match its public class.

### Performance

- The text fallback now indexes strings, comments and bracket pairs once per document snapshot. Repeated guide
  updates no longer rescan the file from offset 0.
- Scope analysis is cached by document stamp, tab size, enabled scope types and file identity, so caret movements
  reuse the same bracket index.
- The `RangeHighlighter` is reused while the scope range is unchanged, and style changes update its renderer in place.
- Controller bookkeeping is confined to the EDT with a concurrent registry, and disposed editors are pruned.
- Large-file scans have safety limits, and performance regression tests cover index reuse and very large files.

## [1.3.0] - 2026-10-26

### Added

- Settings panel at **Settings → Tools → IndentScope** with global enable, native-guide policy, active-editor-only
  mode, scope-type filters, stroke width, corner radius and an optional custom guide color.
- Theme-aware guide color with a dedicated **IndentScope** page under **Settings → Editor → Color Scheme**.
- Status-bar widget that shows the current state and toggles the guide on click.
- Scope-type filtering for blocks `{ }`, parentheses `( )` and brackets `[ ]`. Disabled types fall back outward to
  the nearest enabled enclosing scope instead of hiding the guide entirely.
- Optional guide in console, diff and preview editors. These auxiliary surfaces are independent and disabled by
  default; main editors are always included.
- English and Spanish interface through localized resource bundles.

### Changed

- Settings now persist through a `PersistentStateComponent` in `indentScope.xml`. The legacy `IndentScope.enabled`
  value from `PropertiesComponent` is migrated automatically on first load.
- The guide appearance is configurable: stroke width from 1.0 to 4.0 px and corner radius from 0 to 12 px.
- Native indent guides can be kept visible while the custom guide is active, and the guide can be limited to the
  editor that currently has keyboard focus.
- The plugin now declares `require-restart="true"`, so installation, update, and uninstallation require an IDE
  restart. This guarantees the guide is active immediately after a first install instead of waiting for the next
  manual restart.
- Scope detection now uses the language brace matcher when available and falls back to a text scanner for plain
  files and unsupported editors.
- With multiple carets, only the primary caret defines the current scope. Adding or removing carets refreshes the guide.
- Soft-wrapped scopes skip the continuation rows whose text starts to the left of the guide, so the vertical stroke no
  longer paints over wrapped content. The rounded arms remain on the first and last rows.
- HiDPI rendering keeps stroke width and corner radius in logical pixels. The editor already scales its `Graphics2D`,
  so the guide is no longer at risk of being scaled twice.
- Changes to the IDE's **Show indent guides** option are reapplied while the plugin is active, and the latest user
  preference is preserved for restoration when the plugin is disabled.

### Fixed

- The guide now reaches the closing `}` of a method or constructor whose declaration spans multiple lines, instead of
  stopping at the last body line.
- Classes without methods, such as Lombok DTOs, now show their class-level guide.
- Column-0 guides no longer draw the tiny one-pixel end arms, which looked like stray dots. They are now plain vertical
  lines that start just below the opener line and end just above the closing line, so they never overlap the
  declaration or its brace. Unbraced scopes, such as a top-level Python `def`, cover their last body line.
- Brackets inside strings, comments, backtick template literals, triple-quoted/raw strings and C# verbatim strings no
  longer end the current scope early.
- Allman-style declarations now start the guide at the declaration line instead of the `{` line.
- Top-level unbraced scopes, such as Python `def` and `class` blocks, now show their guide.
- Multi-line empty blocks now show their guide; blocks that open and close on the same line intentionally show none.
- A collapsed fold that hides the scope opener or its closing line now hides the guide instead of leaving a stale
  vertical stroke. Folds inside the scope body keep the outer guide, and expanding the fold restores it immediately.

## [1.2.1] - 2026-10-26

### Changed

- Minimum supported build is now 252 (IntelliJ Platform 2025.2), and the plugin is verified with IntelliJ IDEA 2025.2.6.3
  and 2026.2.3.

### Fixed

- The guide now also appears when the plugin is installed or updated while the IDE is already running: editors that
  were open before the plugin loaded are registered immediately instead of waiting for the editor to be reopened.

## [1.2.0]

### Added

- Rounded indent guide for the current scope, anchored to the scope opener.
- Support for scopes closed by `}`, `)`, and `]`, including nested and multi-line brackets.
- Global toggle action with `Ctrl+Alt+G` / `Cmd+Option+G`, also available from View → Appearance and Find Action.
- Persistent enabled state across IDE restarts.
- IdeaVim support through `<Action>(IndentScope.ToggleGuide)`.

### Changed

- Native indent guides are hidden only while the custom guide is active and restored per editor when disabled.
