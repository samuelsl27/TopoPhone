package es.topophone.buildlogic.quality

/**
 * Lista de SDK de analítica, informes de errores y atribución que nunca pueden entrar en el APK
 * (S001/RF-24, constitución nº 4).
 *
 * Cada regla es un grupo Maven (coincide con el grupo exacto o con sus subgrupos `grupo.*`)
 * o `grupo:prefijo-de-artefacto`.
 */
object TelemetryGuard {

    private val FORBIDDEN_RULES: List<String> = listOf(
        "com.google.firebase",
        "com.google.android.gms:play-services-analytics",
        "com.google.android.gms:play-services-measurement",
        "com.crashlytics",
        "io.fabric",
        "io.sentry",
        "com.microsoft.appcenter",
        "com.bugsnag",
        "com.datadoghq",
        "com.newrelic",
        "com.amplitude",
        "com.mixpanel",
        "io.embrace",
        "com.instabug",
        "com.segment",
        "com.flurry",
        "com.appsflyer",
        "com.adjust.sdk",
    )

    /** @param coordinates dependencias resueltas como `grupo:artefacto:versión`. */
    fun findForbidden(coordinates: Collection<String>): List<String> =
        coordinates.filter(::isForbidden).distinct().sorted()

    private fun isForbidden(coordinate: String): Boolean {
        val parts = coordinate.split(":")
        val group = parts.getOrElse(0) { "" }
        val artifact = parts.getOrElse(1) { "" }
        return FORBIDDEN_RULES.any { rule ->
            val (ruleGroup, artifactPrefix) = rule.split(":", limit = 2).let { it[0] to it.getOrNull(1) }
            val groupMatches = group == ruleGroup || group.startsWith("$ruleGroup.")
            groupMatches && (artifactPrefix == null || artifact.startsWith(artifactPrefix))
        }
    }
}
