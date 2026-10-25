# IndentScope

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
- Works with nested blocks and multi-line calls/conditions.
- Keeps the approved look: accent color, rounded corners, no horizontal arms on body lines.
- Hides native indent guides only while the custom guide is active.
- Restores the native indent-guide setting of each editor when disabled.
- Optional unbraced scopes end at their last body line; they never borrow the closing bracket of an outer block.

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

- IntelliJ Platform 2025.3+ (build 253+).
- The plugin does not depend on a specific language plugin.

## Build

```bash
./gradlew buildPlugin
```

The installable ZIP is generated at:

```
build/distributions/IndentScope-1.0.1-SNAPSHOT.zip
```

Install it from **Settings → Plugins → Install Plugin from Disk**.

## Tests

```bash
./gradlew test
```

The platform tests cover the global toggle, native-guide restoration, persistence, multi-line parentheses and brackets,
and unbraced scopes that must not borrow an outer block's closing brace.

## Project structure

```
src/main/kotlin/
├── CurrentScopeIndentGuideFactoryListener.kt  Editor listener, controller, scope detection and renderer
├── IndentScopeService.kt                      Application service: per-editor state and persistence
└── ToggleIndentScopeGuideAction.kt            Global Show/Hide action

src/main/resources/META-INF/
├── plugin.xml                                 Plugin registration
└── pluginIcon.svg                             Plugin icon

src/test/kotlin/com/access/
└── IndentScopeToggleTest.kt                   Platform regression tests
```
