package com.access

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.ex.EditorEx

/**
 * Application-level owner of the current-scope guide state. It keeps one controller
 * per editor and coordinates the global toggle with the native indent-guide setting.
 */
@Service(Service.Level.APP)
class IndentScopeService : Disposable {

    private val controllers = mutableMapOf<EditorEx, CurrentScopeIndentGuideController>()
    private var disposed = false

    /**
     * Whether the current-scope guide replaces the native indent guides. The value is
     * persisted across IDE restarts and defaults to enabled.
     */
    var enabled: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(ENABLED_KEY, true)
        set(value) {
            if (disposed || enabled == value) return
            // The three-arg overload treats the third argument as the default value:
            // passing `true` unsets the key when enabled and stores "false" otherwise.
            // The two-arg overload would silently unset the key for `false`, making
            // the getter fall back to `true` and breaking the next toggle.
            PropertiesComponent.getInstance().setValue(ENABLED_KEY, value, true)
            controllers.values.toList().forEach { it.setEnabled(value) }
        }

    fun editorCreated(editor: EditorEx) {
        if (disposed || controllers.containsKey(editor)) return

        val controller = CurrentScopeIndentGuideController(editor)
        controllers[editor] = controller
        controller.setEnabled(enabled)
    }

    fun editorReleased(editor: EditorEx) {
        controllers.remove(editor)?.dispose()
    }

    override fun dispose() {
        if (disposed) return
        disposed = true

        val openControllers = controllers.values.toList()
        controllers.clear()
        openControllers.forEach { it.dispose() }
    }

    companion object {
        private const val ENABLED_KEY = "IndentScope.enabled"

        fun getInstance(): IndentScopeService =
            ApplicationManager.getApplication().getService(IndentScopeService::class.java)
    }
}
