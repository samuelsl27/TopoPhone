package es.topophone.buildlogic.quality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Sin analítica ni informes de errores en el APK (S001/RF-24, constitución nº 4). */
class TelemetryGuardTest {

    @Test
    fun `S001 RF-24 las dependencias habituales de la app no son telemetria`() {
        val coordinates = listOf(
            "androidx.compose.material3:material3:1.4.0",
            "androidx.datastore:datastore-preferences:1.2.1",
            "com.google.dagger:hilt-android:2.60.1",
            "com.google.android.gms:play-services-location:21.3.0",
            "com.mikepenz:aboutlibraries-core:15.3.0",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0",
        )

        assertEquals(emptyList<String>(), TelemetryGuard.findForbidden(coordinates))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "com.google.firebase:firebase-analytics:23.0.0",
            "com.google.firebase:firebase-crashlytics:20.0.0",
            "com.google.android.gms:play-services-analytics:18.1.1",
            "com.google.android.gms:play-services-measurement-api:23.0.0",
            "com.crashlytics.sdk.android:crashlytics:2.10.1",
            "io.sentry:sentry-android:8.0.0",
            "com.microsoft.appcenter:appcenter-crashes:5.0.6",
            "com.bugsnag:bugsnag-android:6.10.0",
            "com.datadoghq:dd-sdk-android-rum:2.20.0",
            "com.newrelic.agent.android:android-agent:7.6.2",
            "com.amplitude:analytics-android:1.20.0",
            "com.mixpanel.android:mixpanel-android:8.0.0",
            "io.embrace:embrace-android-sdk:7.0.0",
            "com.instabug.library:instabug:14.0.0",
            "com.segment.analytics.kotlin:android:1.19.0",
            "com.flurry.android:analytics:14.4.0",
            "com.appsflyer:af-android-sdk:6.15.0",
        ],
    )
    fun `S001 RF-24 cada SDK de telemetria conocido se detecta`(coordinate: String) {
        assertEquals(listOf(coordinate), TelemetryGuard.findForbidden(listOf(coordinate)))
    }

    @Test
    fun `S001 RF-24 un grupo que solo empieza igual no se confunde con uno prohibido`() {
        val coordinates = listOf("io.sentryx:otra:1.0", "com.google.firebaseui:firebase-ui-auth:9.0.0", "com.amplitudeless:x:1.0")

        assertEquals(emptyList<String>(), TelemetryGuard.findForbidden(coordinates))
    }

    @Test
    fun `S001 RF-24 los hallazgos salen ordenados y sin repetir`() {
        val coordinates = listOf(
            "io.sentry:sentry-android:8.0.0",
            "com.google.firebase:firebase-analytics:23.0.0",
            "io.sentry:sentry-android:8.0.0",
        )

        assertEquals(
            listOf("com.google.firebase:firebase-analytics:23.0.0", "io.sentry:sentry-android:8.0.0"),
            TelemetryGuard.findForbidden(coordinates),
        )
    }
}
