package com.access

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.PropertyResourceBundle

class IndentScopeBundleTest {

    @Test
    fun englishBundleContainsSettingsStrings() {
        val bundle = load("messages/IndentScopeBundle.properties")

        assertEquals("IndentScope Guide", bundle.getString("action.toggle.text"))
        assertEquals("Enable IndentScope guide", bundle.getString("settings.enabled"))
    }

    @Test
    fun spanishBundleContainsTranslations() {
        val bundle = load("messages/IndentScopeBundle_es.properties")

        assertEquals("Guía de IndentScope", bundle.getString("action.toggle.text"))
        assertEquals("Activar la guía de IndentScope", bundle.getString("settings.enabled"))
        assertEquals("IndentScope (desactivada)", bundle.getString("statusBar.disabled"))
    }

    private fun load(path: String): PropertyResourceBundle {
        val stream = javaClass.classLoader.getResourceAsStream(path)
        requireNotNull(stream) { "Missing bundle resource: $path" }
        return stream.bufferedReader(Charsets.UTF_8).use { PropertyResourceBundle(it) }
    }
}
