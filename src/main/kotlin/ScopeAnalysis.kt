package com.access

import com.intellij.openapi.editor.ex.EditorEx

/**
 * Everything needed to resolve a guide for one document snapshot.
 *
 * The controller keeps one analysis while the document stamp, tab size, allowed
 * scope types and file identity stay unchanged. Reusing it preserves the
 * [TextBracketIndex] built by [BracketScanner], so caret movements and repeated
 * updates no longer rescan the document from offset 0.
 */
internal class ScopeAnalysis(
    val detector: ScopeDetector,
) {
    companion object {
        fun create(
            editor: EditorEx,
            tabSize: Int,
            allowedOpeners: Set<Char>,
        ): ScopeAnalysis {
            val document = editor.document
            val chars = document.charsSequence
            val lines = DocumentLineMap(document)
            val brackets = BracketScanner(editor, chars, lines)
            return ScopeAnalysis(
                detector = ScopeDetector(chars, lines, brackets, tabSize, allowedOpeners),
            )
        }
    }
}
