package com.access

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import java.awt.Component
import java.awt.event.MouseEvent

/**
 * Status-bar indicator that also works as a quick toggle. The widget is optional:
 * users can hide it from the status-bar settings like any other widget.
 */
class IndentScopeStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = WIDGET_ID

    override fun getDisplayName(): String = IndentScopeBundle.message("statusBar.displayName")

    override fun isEnabledByDefault(): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget = IndentScopeStatusBarWidget()

    companion object {
        const val WIDGET_ID = "IndentScope.status"
    }
}

private class IndentScopeStatusBarWidget : StatusBarWidget, StatusBarWidget.TextPresentation {

    private var statusBar: StatusBar? = null
    private val settings: IndentScopeSettings
        get() = IndentScopeSettings.getInstance()
    private val connection = ApplicationManager.getApplication().messageBus.connect(this)

    init {
        connection.subscribe(
            IndentScopeSettingsListener.TOPIC,
            object : IndentScopeSettingsListener {
                override fun settingsChanged() {
                    statusBar?.updateWidget(ID())
                }
            },
        )
    }

    override fun ID(): String = IndentScopeStatusBarWidgetFactory.WIDGET_ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
    }

    override fun dispose() {
        statusBar = null
    }

    override fun getText(): String =
        if (settings.enabled) {
            IndentScopeBundle.message("statusBar.enabled")
        } else {
            IndentScopeBundle.message("statusBar.disabled")
        }

    override fun getTooltipText(): String =
        if (settings.enabled) {
            IndentScopeBundle.message("statusBar.tooltip.enabled")
        } else {
            IndentScopeBundle.message("statusBar.tooltip.disabled")
        }

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getClickConsumer(): Consumer<MouseEvent> =
        Consumer { IndentScopeService.getInstance().enabled = !settings.enabled }
}
