# IndentScope

[![Build](https://github.com/angelf015/IndentScope/actions/workflows/build.yml/badge.svg)](https://github.com/angelf015/IndentScope/actions/workflows/build.yml)
[![JetBrains Marketplace](https://img.shields.io/jetbrains/plugin/v/35438-indentscope.svg)](https://plugins.jetbrains.com/plugin/35438-indentscope)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/35438-indentscope.svg)](https://plugins.jetbrains.com/plugin/35438-indentscope)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

IntelliJ Platform plugin that highlights the indent guide of the **current scope** with a rounded bracket and hides the
native indent guides while it is active.

The guide starts on the line that opens the scope and ends at its closing bracket, so it works with blocks, multi-line
parentheses and multi-line brackets:

```kotlin
fun main() {
    someFunction(          // guide starts here
        firstArgument,
        secondArgument,
    )                      // guide ends here
}
```

## Features

- Rounded indent guide for the scope containing the caret.
- Supports scopes closed by `}`, `)` and `]`.
- Shows the class-level guide for DTOs and other classes without methods.
- Works with nested blocks and multi-line calls/conditions.
- Shows top-level unbraced scopes, such as Python `def` and `class` blocks.
- Shows multi-line empty blocks; single-line blocks intentionally draw no vertical guide.
- Ignores brackets inside strings, comments, template literals and raw strings.
- Anchors Allman-style declarations to the declaration line instead of the `{` line.
- Keeps the approved look: accent color, rounded corners, no horizontal arms on body lines.
- Hides native indent guides only while the custom guide is active.
- Restores the native indent-guide setting of each editor when disabled, including changes made through the IDE while
  the plugin is active.
- Optional unbraced scopes end at their last body line; they never borrow the closing bracket of an outer block.
- Handles collapsed folds, soft-wrapped lines, multiple carets and HiDPI consistently.
- Reuses the scope analysis and highlighter while the document and scope are unchanged, keeping large files responsive.
- Console, diff and preview editors are supported as opt-in surfaces.
- Settings panel in **Settings → Tools → IndentScope** for behavior and appearance.
- Theme-aware guide color, also customizable per color scheme.
- Status-bar widget for a quick on/off toggle.
- English and Spanish UI.

## Installation

From the IDE:

1. Open **Settings → Plugins → Marketplace**.
2. Search for **IndentScope** and click **Install**.
3. Restart the IDE when prompted. The restart is required for the plugin to activate.

You can also open the [Marketplace page](https://plugins.jetbrains.com/plugin/35438-indentscope) directly.

From a downloaded release:

1. Download `IndentScope-1.3.0.zip` from the
   [releases page](https://github.com/angelf015/IndentScope/releases).
2. Open **Settings → Plugins**, click the gear icon → **Install Plugin from Disk...**, and select the ZIP.
3. Restart the IDE when prompted. The restart is required for the plugin to activate.

## Toggle the guide

The feature can be disabled globally, for example while presenting code:

| Action | macOS | Windows / Linux |
| --- | --- | --- |
| Toggle IndentScope guide | `Cmd+Option+G` | `Ctrl+Alt+G` |

It is also available in **View → Appearance → IndentScope Guide** and in **Find Action** (`Cmd+Shift+A` /
`Ctrl+Shift+A`).

- The state is persisted across IDE restarts.
- Toggling applies immediately to every open editor and project.
- New editors respect the current state.
- While disabled, no scope scanning is performed.
- Shortcuts can be reassigned in **Settings → Keymap**.

The status bar also shows an **IndentScope** widget: click it to toggle the guide, or hide it from the status-bar
settings like any other widget.

## Settings

Open **Settings → Tools → IndentScope** to customize the plugin:

| Setting | Default | Description |
| --- | --- | --- |
| Enable IndentScope guide | On | Global toggle, equivalent to the action and the status-bar widget. |
| Keep native indent guides visible | Off | Draws the custom guide without hiding the IDE's indentation guides. |
| Show the guide only in the active editor | Off | Useful with split editors: only the pane with keyboard focus shows the custom guide. |
| Blocks `{ }` | On | Enables guides for brace-delimited scopes, including indentation-only Python blocks. |
| Parentheses `( )` | On | Enables guides for multi-line parentheses. |
| Brackets `[ ]` | On | Enables guides for multi-line brackets. |
| Stroke width | 1.5 px | Guide thickness, from 1.0 to 4.0 px in 0.5 px steps. |
| Corner radius | 4 px | Rounding of the top and bottom corners, from 0 to 12 px. |
| Use a custom guide color | Off | Overrides the theme color with a color chosen in the settings panel. |
| Console editors | Off | Draws the guide in console output editors. |
| Diff editors | Off | Draws the guide in diff viewers. |
| Preview editors | Off | Draws the guide in preview editors, such as search results or code-style previews. |

If a scope type is disabled, the guide falls back outward to the nearest enabled enclosing scope instead of
disappearing. At least one scope type must remain enabled. Main editors are always included; auxiliary editor types are
independent and off by default.

### Visual robustness

- **Collapsed folds:** when the line that opens the scope or its closing line is hidden by a collapsed fold, the guide
  hides. A fold inside the body keeps the outer guide, and expanding the fold restores it immediately.
- **Soft wrap:** the vertical stroke skips continuation rows whose text starts to its left, so it does not paint over
  wrapped content. The first and last rows keep the rounded arms.
- **Multiple carets:** the primary caret defines the current scope. Adding or removing carets refreshes the guide.
- **HiDPI:** stroke width and corner radius are logical pixels. The editor already scales its `Graphics2D`, so applying
  the display scale again would double the size.
- **Column 0:** when the opener has no leading whitespace, the guide is a clean vertical line without artificial arms.
- **External setting changes:** if **Show indent guides** changes in the IDE while the plugin is active, the native-guide
  policy is reapplied and the latest preference is preserved for when the plugin is disabled.

### Guide color

Without a custom color, the guide follows the active color scheme. You can change it in
**Settings → Editor → Color Scheme → IndentScope**, where it follows the current theme and its light/dark variants.
The resolution order is:

1. Custom color from **Settings → Tools → IndentScope**.
2. **IndentScope guide** color from the active color scheme.
3. The scheme's selected indent-guide color.
4. The scheme's normal indent-guide color.

## Localization

The plugin ships with an English base bundle and a Spanish translation. All user-visible strings follow the IDE
language automatically.

## IdeaVim

The action ID is stable, so it can be mapped directly from `.ideavimrc`:

```vim
" Toggle with <leader>ig (leader defaults to \)
nnoremap <leader>ig <Action>(IndentScope.ToggleGuide)
```

To keep the same macOS shortcut inside Vim:

```vim
nnoremap <D-A-g> <Action>(IndentScope.ToggleGuide)
```

Useful commands while configuring:

```vim
:actionlist IndentScope
:action IndentScope.ToggleGuide
:source ~/.ideavimrc
```

## Requirements

- IntelliJ Platform 2025.2+ (build 252+), including 2025.3 and 2026.x.
- Verified with IntelliJ IDEA 2025.2.6.3, 2025.3.6.1, 2026.1.5 and 2026.2.3.
- The plugin does not depend on a specific language plugin.

## Build

Requirements: JDK 21. The Gradle wrapper downloads the rest, and the build declares a JDK 21 toolchain explicitly.

```bash
./gradlew build buildPlugin verifyPluginStructure
```

The `build` task also runs ktlint through the `check` lifecycle. The installable ZIP is generated at:

```
build/distributions/IndentScope-1.3.0.zip
```

Install it from **Settings → Plugins → Install Plugin from Disk**.

IndentScope declares `require-restart="true"`, so the IDE always asks for a restart after installing, updating, or
uninstalling it. Restart when prompted: the guide is active immediately afterwards.

To run a sandbox IDE with the plugin installed:

```bash
./gradlew runIde
```

To check binary compatibility against the supported IDEs:

```bash
./gradlew verifyPlugin
```

Android Studio uses a large installer, so it is opt-in for local verification:

```bash
./gradlew verifyPlugin -PverifyAndroidStudio
```

To generate the coverage report:

```bash
./gradlew koverHtmlReport
./gradlew koverVerify
```

The HTML report is written to `build/reports/kover/html/index.html`. `koverVerify` fails the build if line coverage drops
below 80 %.

## Tests

```bash
./gradlew test
```

The platform tests cover the global toggle, native-guide restoration, persistence, multi-line parentheses and brackets,
unbraced scopes that must not borrow an outer block's closing brace, top-level Python scopes, empty and single-line
blocks, mixed indentation, strings/comments with bracket-like content, backticks, triple quotes, nested comments,
Allman-style declarations, settings persistence and migration, scope-type filtering with outward fallback, the settings
panel, the color settings page, the status-bar widget, folded scopes, folds inside the body, multiple carets, auxiliary
editor filtering, external indent-guide changes, real soft-wrapped editors, controller/service disposal, background
editor registration and concurrent registration. A separate pure test suite covers the scope detector, fold visibility,
editor-kind filtering and soft-wrap path segmentation without starting the IDE, and a performance suite verifies index
reuse and guide resolution in files with 80 000–100 000 lines. A bundle test checks the English and Spanish resources.
The suite currently has **88 tests** (60 platform/integration, 23 pure, 3 performance and 2 bundle tests).

## CI and quality

Every push and pull request builds, tests, lints and packages the plugin on Ubuntu, macOS and Windows. The workflow also
validates the Gradle wrapper and publishes the plugin ZIP and the coverage report as artifacts. Compatibility verification
runs on every push to `main`, on release tags, on a weekly schedule and on demand; the scheduled job also verifies
Android Studio.

```bash
./gradlew ktlintCheck
./gradlew koverHtmlReport
./gradlew verifyPlugin -PverifyAndroidStudio
```

Formatting rules shared by IntelliJ IDEA and ktlint live in `.editorconfig`, and the CI configuration is defined in
`.github/workflows/build.yml`.

## Project structure

```
src/main/kotlin/
├── CurrentScopeIndentGuideFactoryListener.kt  Editor factory listener
├── CurrentScopeIndentGuideController.kt       Per-editor state, lifecycle and guide updates
├── ActiveIndentGuideRenderer.kt               Highlighter renderer and rounded path geometry
├── ScopeAnalysis.kt                           Cached detector and bracket index per document snapshot
├── BracketScanner.kt                          Language-aware bracket scanner and plain-text fallback
├── TextBracketIndex.kt                        One-pass lexical and bracket index for the fallback
├── ScopeDetector.kt                           Pure scope resolution, line map and guide model
├── GuideRobustness.kt                         Fold, editor-kind and soft-wrap visibility helpers
├── IndentScopeService.kt                      Application service: per-editor controllers and refresh
├── IndentScopeSettings.kt                     Persisted settings, normalization and legacy migration
├── IndentScopeConfigurable.kt                 Settings → Tools → IndentScope panel
├── IndentScopeColors.kt                       Color key and theme-aware color resolution
├── IndentScopeColorSettingsPage.kt            Settings → Editor → Color Scheme page
├── IndentScopeStatusBarWidgetFactory.kt       Status-bar widget factory and quick toggle
├── IndentScopeBundle.kt                       Localized message access
└── ToggleIndentScopeGuideAction.kt            Global Show/Hide action

src/main/resources/
├── META-INF/
│   ├── plugin.xml                             Plugin registration
│   └── pluginIcon.svg                         Plugin icon
└── messages/
    ├── IndentScopeBundle.properties           English strings
    └── IndentScopeBundle_es.properties        Spanish strings

src/test/kotlin/com/access/
├── IndentScopeToggleTest.kt                   Platform and integration regression tests
├── ScopeDetectorTest.kt                       Pure detector and visibility tests
├── ScopePerformanceTest.kt                    Index-reuse and large-file tests
└── IndentScopeBundleTest.kt                   English/Spanish resource tests
```

## Contributing

Issues and pull requests are welcome at
[github.com/angelf015/IndentScope](https://github.com/angelf015/IndentScope).

## License

Released under the [Apache License 2.0](LICENSE).
