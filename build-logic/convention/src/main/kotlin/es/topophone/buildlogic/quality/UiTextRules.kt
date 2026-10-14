package es.topophone.buildlogic.quality

/**
 * Detecta textos visibles escritos directamente en el código Compose (S001/RF-19).
 * Android Lint (`HardcodedText`) solo cubre los layouts XML, así que esta regla
 * vigila los argumentos de texto de Compose: `Text("…")`, `text =`, `contentDescription =`,
 * `label =`, `title =`, `placeholder =` y `supportingText =`.
 *
 * Excepciones: cadenas vacías, cadenas formadas solo por plantillas (`"$valor"`), líneas
 * comentadas y líneas marcadas con `// topophone:literal-ok` (p. ej. nombres propios).
 */
object UiTextRules {

    private const val ALLOW_MARKER = "topophone:literal-ok"

    private val UI_TEXT_LITERAL = Regex(
        """(?:\bText\(\s*|\b(?:text|contentDescription|label|title|placeholder|supportingText)\s*=\s*)"((?:[^"\\]|\\.)*)"""",
    )
    private val ONLY_TEMPLATES = Regex("""^(?:\$\{[^}]*\}|\$\w+|\s)*$""")

    /** @param sources ruta relativa → contenido. Los hallazgos usan [ModuleRuleViolation] con `location = ruta:línea`. */
    fun findHardcoded(sources: Map<String, String>, module: String = ""): List<ModuleRuleViolation> =
        sources.toSortedMap().flatMap { (file, content) ->
            content.lineSequence().flatMapIndexed { index, line ->
                if (line.trimStart().startsWith("//") || ALLOW_MARKER in line) return@flatMapIndexed emptySequence()
                UI_TEXT_LITERAL.findAll(line)
                    .map { it.groupValues[1] }
                    .filterNot { it.isEmpty() || ONLY_TEMPLATES.matches(it) }
                    .map { literal ->
                        ModuleRuleViolation(
                            module,
                            "texto visible literal \"$literal\": usa un recurso de strings.xml",
                            "$file:${index + 1}",
                        )
                    }
            }.toList()
        }
}
