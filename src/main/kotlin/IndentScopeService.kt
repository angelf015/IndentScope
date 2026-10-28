package com.access

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.beans.PropertyChangeListener
import java.util.concurrent.ConcurrentHashMap

/**
 * Application-level owner of the current-scope guide state. It keeps one controller
 * per editor and coordinates the global toggle with the native indent-guide setting.
 *
 * The controller map is concurrent because editor lifecycle events can arrive from
 * different platform threads, but every operation that touches an editor (markup
 * model, settings) is confined to the EDT by the public methods below.
 */
@Service(Service.Level.APP)
class IndentScopeService : Disposable {

    private val controllers = ConcurrentHashMap<EditorEx, CurrentScopeIndentGuideController>()
    private val messageBusConnection = ApplicationManager.getApplication().messageBus.connect(this)
    private val listenerDisposable = Disposer.newDisposable("IndentScopeService")
    private val externalIndentGuidesListener = PropertyChangeListener { event ->
        if (event.propertyName ==
            EditorSettingsExternalizable.PropNames.PROP_IS_INDENT_GUIDES_SHOWN
        ) {
            onExternalIndentGuidesChanged()
        }
    }

    @Volatile
    private var disposed = false
    private val settings: IndentScopeSettings
        get() = IndentScopeSettings.getInstance()

    init {
        messageBusConnection.subscribe(
            IndentScopeSettingsListener.TOPIC,
            object : IndentScopeSettingsListener {
                override fun settingsChanged() {
                    onSettingsChanged()
                }
            },
        )

        // The IDE's "Show indent guides" option can change while the plugin is
        // active. React after the platform has refreshed every editor's local
        // setting, so the plugin's native-guide policy is never left in a stale state.
        EditorSettingsExternalizable.getInstance().addPropertyChangeListener(
            externalIndentGuidesListener,
            listenerDisposable,
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
            // every open editor. Keeping this facade preserves the application API.
            settings.enabled = value
        }

    /** Effective state for [project], including its optional override. */
    fun isEnabled(project: Project?): Boolean = settings.isEnabled(project)

    /**
     * Toggles the effective state. Projects that inherit update the global setting;
     * projects with an override update only their own value.
     */
    fun setEnabled(project: Project?, value: Boolean) {
        if (disposed) return
        settings.setEnabled(project, value)
    }

    fun editorCreated(editor: EditorEx) {
        if (disposed) return
        if (!ApplicationManager.getApplication().isDispatchThread) {
            runOnEdt { editorCreated(editor) }
            return
        }
        if (editor.isDisposed || editor.isOneLineMode) return

        pruneDisposedControllers()

        if (controllers.containsKey(editor)) return
        if (!settings.shouldShowInEditor(editor.editorKind, editor.project)) return

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
            runOnEdt { registerOpenEditorsNow() }
        }
    }

    private fun registerOpenEditorsNow() {
        if (disposed) return
        pruneDisposedControllers()

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
        if (disposed) return
        if (!ApplicationManager.getApplication().isDispatchThread) {
            runOnEdt { editorReleased(editor) }
            return
        }

        controllers.remove(editor)?.dispose()
    }

    private fun onSettingsChanged() {
        if (disposed) return
        if (!ApplicationManager.getApplication().isDispatchThread) {
            runOnEdt { onSettingsChanged() }
            return
        }

        pruneDisposedControllers()

        // Editor-type filters can change while editors of that kind are already
        // open. Dispose the controllers that are no longer wanted so they also
        // restore the native indent guides, then register the newly enabled ones.
        val disabled = controllers.filterKeys { !settings.shouldShowInEditor(it.editorKind, it.project) }
        disabled.forEach { (editor, controller) ->
            controllers.remove(editor, controller)
            controller.dispose()
        }

        registerOpenEditors()
        controllers.values.toList().forEach { it.onSettingsChanged() }
    }

    private fun onExternalIndentGuidesChanged() {
        if (disposed) return
        runOnEdt {
            if (!disposed) {
                controllers.values.toList().forEach { it.onExternalIndentGuidesChanged() }
            }
        }
    }

    private fun pruneDisposedControllers() {
        controllers.forEach { (editor, controller) ->
            if (editor.isDisposed && controllers.remove(editor, controller)) {
                controller.dispose()
            }
        }
    }

    private fun runOnEdt(action: () -> Unit) {
        if (disposed) return
        ApplicationManager.getApplication().invokeLater(
            {
                if (!disposed) action()
            },
            ModalityState.nonModal(),
        )
    }

    /** Number of editors currently tracked. Used by diagnostics and lifecycle tests. */
    internal fun registeredControllerCount(): Int = controllers.size

    /** Whether the given editor currently has a controller. Used by lifecycle tests. */
    internal fun hasController(editor: EditorEx): Boolean = controllers.containsKey(editor)

    override fun dispose() {
        if (disposed) return
        disposed = true

        val openControllers = controllers.values.toList()
        controllers.clear()
        openControllers.forEach { it.dispose() }

        // Release the listeners eagerly: tests create short-lived service instances,
        // and the platform-disposed service must not keep receiving events.
        messageBusConnection.disconnect()
        Disposer.dispose(listenerDisposable)
    }

    companion object {
        private val LOG = Logger.getInstance(IndentScopeService::class.java)

        fun getInstance(): IndentScopeService = ApplicationManager.getApplication().getService(IndentScopeService::class.java)
    }
}
