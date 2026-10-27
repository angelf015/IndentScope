package com.access

import com.intellij.DynamicBundle
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.IndentScopeBundle"

/**
 * Single entry point for all user-visible strings. The default bundle is English
 * and `IndentScopeBundle_es.properties` contains the Spanish translation.
 */
object IndentScopeBundle : DynamicBundle(BUNDLE) {
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)
}
