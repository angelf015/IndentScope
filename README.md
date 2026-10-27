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
- Restores the native indent-guide setting of each editor when disabled.
- Optional unbraced scopes end at their last body line; they never borrow the closing bracket of an outer block.

## Installation

From the IDE:

1. Open **Settings → Plugins → Marketplace**.
2. Search for **IndentScope** and click **Install**.
3. Restart the IDE when prompted. The restart is required for the plugin to activate.

You can also open the [Marketplace page](https://plugins.jetbrains.com/plugin/35438-indentscope) directly.

From a downloaded release:

1. Download `IndentScope-1.2.2.zip` from the
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
- Verified with IntelliJ IDEA 2025.2.6.3 and 2026.2.3.
- The plugin does not depend on a specific language plugin.

## Build

Requirements: JDK 21. The Gradle wrapper downloads the rest.

```bash
./gradlew build buildPlugin verifyPluginStructure
```

The installable ZIP is generated at:

```
build/distributions/IndentScope-1.2.2.zip
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

## Tests

```bash
./gradlew test
```

The platform tests cover the global toggle, native-guide restoration, persistence, multi-line parentheses and brackets,
unbraced scopes that must not borrow an outer block's closing brace, top-level Python scopes, empty and single-line
blocks, mixed indentation, strings/comments with bracket-like content, backticks, triple quotes, nested comments and
Allman-style declarations. A separate pure test suite covers the scope detector without starting the IDE.

## Project structure

```
src/main/kotlin/
├── CurrentScopeIndentGuideFactoryListener.kt  Editor listener, controller and renderer
├── BracketScanner.kt                          Language-aware bracket scanner and plain-text fallback
├── ScopeDetector.kt                           Pure scope resolution, line map and guide model
├── IndentScopeService.kt                      Application service: per-editor state and persistence
└── ToggleIndentScopeGuideAction.kt            Global Show/Hide action

src/main/resources/META-INF/
├── plugin.xml                                 Plugin registration
└── pluginIcon.svg                             Plugin icon

src/test/kotlin/com/access/
├── IndentScopeToggleTest.kt                   Platform regression tests
└── ScopeDetectorTest.kt                       Pure detector tests without BasePlatformTestCase
```

## Contributing

Issues and pull requests are welcome at
[github.com/angelf015/IndentScope](https://github.com/angelf015/IndentScope).

## License

Released under the [Apache License 2.0](LICENSE).
