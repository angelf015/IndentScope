<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# IndentScope Changelog

## [Unreleased]

### Added

- Rounded indent guide for the current scope, anchored to the scope opener.
- Support for scopes closed by `}`, `)`, and `]`, including nested and multi-line brackets.
- Global toggle action with `Ctrl+Alt+G` / `Cmd+Option+G`, also available from View → Appearance and Find Action.
- Persistent enabled state across IDE restarts.
- IdeaVim support through `<Action>(IndentScope.ToggleGuide)`.

### Changed

- Native indent guides are hidden only while the custom guide is active and restored per editor when disabled.
