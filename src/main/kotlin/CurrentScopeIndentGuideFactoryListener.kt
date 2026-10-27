package com.access

import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.ex.EditorEx

/**
 * Registers editors with [IndentScopeService]. The application service owns the
 * per-editor controllers and is disposed by the platform, so the listener does not
 * need its own lifecycle cleanup.
 */
class CurrentScopeIndentGuideFactoryListener : EditorFactoryListener {
    private val service: IndentScopeService by lazy { IndentScopeService.getInstance() }

    init {
        // When the plugin is installed dynamically, the IDE does not recreate the
        // editors that are already open, so the factory listener would never see them.
        service.registerOpenEditors()
    }

    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor as? EditorEx ?: return
        if (editor.isOneLineMode) return

        service.editorCreated(editor)
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        val editor = event.editor as? EditorEx ?: return
        service.editorReleased(editor)
    }
}
