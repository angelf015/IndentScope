package com.access

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx

/**
 * Application-level owner of the current-scope guide state. It keeps one controller
 * per editor and coordinates the global toggle with the native indent-guide setting.
 */
@Service(Service.Level.APP)
class IndentScopeService : Disposable {

    private val controllers = mutableMapOf<EditorEx, CurrentScopeIndentGuideController>()
    private var disposed = false
    private val settings: IndentScopeSettings
        get() = IndentScopeSettings.getInstance()

    init {
        val connection = ApplicationManager.getApplication().messageBus.connect(this)
        connection.subscribe(
            IndentScopeSettingsListener.TOPIC,
            object : IndentScopeSettingsListener {
                override fun settingsChanged() {
                    onSettingsChanged()
                }
            },
        )
    }

    /**
     * Whether the current-scope guide replaces the native indent guides. The value is
     * persisted across IDE restarts and defaults to enabled.
     */
    var enabled: Boolean
        get() = settings.enabled
        set(value) {
            if (disposed || settings.enabled == value) return
            // The settings service publishes the change and this service refreshes
            // every open editor. Keeping this facade preserves the action and test API.
            settings.enabled = value
        }

    fun editorCreated(editor: EditorEx) {
        if (disposed || controllers.containsKey(editor)) return

        val controller = CurrentScopeIndentGuideController(editor)
        controllers[editor] = controller
        controller.onSettingsChanged()
    }

    /**
     * Registers editors that already exist when the plugin is loaded dynamically
     * (for example, after installing it from disk while the IDE is running). Editor
     * factory listeners only receive editors created after registration, so without
     * this step the current editor would stay without a controller until it is closed
     * and reopened.
     */
    fun registerOpenEditors() {
        if (disposed) return
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            registerOpenEditorsNow()
        } else {
            application.invokeLater({ registerOpenEditorsNow() }, ModalityState.nonModal())
        }
    }

    private fun registerOpenEditorsNow() {
        if (disposed) return
        val before = controllers.size
        EditorFactory.getInstance().allEditors.forEach { editor ->
            val editorEx = editor as? EditorEx ?: return@forEach
            if (editorEx.isDisposed || editorEx.isOneLineMode) return@forEach
            editorCreated(editorEx)
        }
        val added = controllers.size - before
        if (added > 0) {
            LOG.info("IndentScope: registered $added open editor(s)")
        }
    }

    fun editorReleased(editor: EditorEx) {
        controllers.remove(editor)?.dispose()
    }

    private fun onSettingsChanged() {
        if (disposed) return
        registerOpenEditors()
        controllers.values.toList().forEach { it.onSettingsChanged() }
    }

    override fun dispose() {
        if (disposed) return
        disposed = true

        val openControllers = controllers.values.toList()
        controllers.clear()
        openControllers.forEach { it.dispose() }
    }

    companion object {
        private val LOG = Logger.getInstance(IndentScopeService::class.java)

        fun getInstance(): IndentScopeService =
            ApplicationManager.getApplication().getService(IndentScopeService::class.java)
    }
}
