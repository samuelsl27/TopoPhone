package es.topophone.app.ui.options

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import io.kotest.matchers.shouldBe

/** Monta [content] con la letra a [fontScale] (1,0 o 1,3) sobre el tema claro. */
internal fun ComposeContentTestRule.setScaledContent(fontScale: Float, content: @Composable BoxScope.() -> Unit) {
    setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { Box(Modifier.fillMaxSize(), content = content) }
        }
    }
}

internal fun walkSemantics(node: SemanticsNode, visit: (SemanticsNode) -> Unit) {
    visit(node)
    node.children.forEach { walkSemantics(it, visit) }
}

/** Ningún texto se elide ni se sale de sus límites (S059/RNF específicos). */
internal fun ComposeContentTestRule.assertNoClippedText() {
    // Con un menú abierto hay dos raíces: la pantalla y la ventana del menú.
    onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach { root -> assertNoClippedText(root) }
}

private fun assertNoClippedText(root: SemanticsNode) {
    walkSemantics(root) { node ->
        val results = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        results.forEach { result ->
            (0 until result.lineCount).none { result.isLineEllipsized(it) } shouldBe true
            (result.size.width <= result.layoutInput.constraints.maxWidth) shouldBe true
            (result.size.height <= result.layoutInput.constraints.maxHeight) shouldBe true
        }
    }
}

/** Cada control que se toca tiene un texto o una descripción para TalkBack (S059/RNF-T8). */
internal fun ComposeContentTestRule.assertTalkBackLabels() {
    onAllNodes(isRoot()).fetchSemanticsNodes().forEach { root ->
        walkSemantics(root) { node ->
            if (node.config.getOrNull(SemanticsActions.OnClick) != null) {
                val text = node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("")
                val description = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString("")
                (text.isNotBlank() || description.isNotBlank()) shouldBe true
            }
        }
    }
}
