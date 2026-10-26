<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# IndentScope Changelog

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
