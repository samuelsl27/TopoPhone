package es.topophone.core.formats.backup

import es.topophone.core.formats.csv.ExportFileName
import java.time.Instant
import java.time.ZoneId

/**
 * Nombres propuestos, con la fecha y hora locales del móvil y las reglas de caracteres de S012/RF-19 (S013/RF-4):
 * la copia `TopoPhone_<AAAAMMDD-HHMM>.zip`, el paquete `<proyecto>_<AAAAMMDD-HHMM>.topoproj`, las copias automáticas
 * `TopoPhone_auto_…` (RF-19) y las del estado anterior a una restauración `TopoPhone_antes-de-restaurar_…` (RF-7).
 */
object BackupFileNames {
    const val PACKAGE_EXTENSION = "topoproj"
    private const val APP = "TopoPhone"

    /** Una copia automática; algunos proveedores añaden " (1)" si el nombre ya existe. */
    private val AUTOMATIC = Regex("""${APP}_auto_\d{8}-\d{4}( \(\d+\))?\.zip""")

    fun backup(at: Instant, zone: ZoneId): String = "${APP}_${ExportFileName.stamp(at, zone)}.zip"

    fun projectPackage(projectName: String, at: Instant, zone: ZoneId): String =
        "${ExportFileName.projectPart(projectName)}_${ExportFileName.stamp(at, zone)}.$PACKAGE_EXTENSION"

    fun automatic(at: Instant, zone: ZoneId): String = "${APP}_auto_${ExportFileName.stamp(at, zone)}.zip"

    fun beforeRestore(at: Instant, zone: ZoneId): String = "${APP}_antes-de-restaurar_${ExportFileName.stamp(at, zone)}.zip"

    fun isAutomatic(name: String): Boolean = AUTOMATIC.matches(name)
}

/** Cuándo toca una copia automática y cuáles sobran en la carpeta (S013/RF-18, RF-19). */
object AutoBackupPolicy {
    const val INTERVAL_MS = 60 * 60 * 1000L
    const val KEEP = 24

    /** Cada cuánto se comprueba si toca mientras la app está abierta (plan, A7). */
    const val CHECK_EVERY_MS = 5 * 60 * 1000L

    /**
     * S013/RF-18: con carpeta, con cambios sin copiar y una hora después de la última copia buena. Si el reloj del
     * móvil ha vuelto atrás (la última es "del futuro"), toca: no se espera horas a que el reloj la alcance.
     */
    fun due(nowMs: Long, lastSuccessMs: Long?, pendingChanges: Boolean, folderSet: Boolean): Boolean {
        if (!folderSet || !pendingChanges) return false
        if (lastSuccessMs == null || nowMs < lastSuccessMs) return true
        return nowMs - lastSuccessMs >= INTERVAL_MS
    }

    /** S013/RF-19: las copias automáticas que pasan de las [KEEP] más recientes; los demás ficheros no se tocan. */
    fun surplus(names: List<String>): List<String> = names.filter(BackupFileNames::isAutomatic)
        .sortedWith(compareByDescending<String> { it.substringAfter("_auto_").take(STAMP_LENGTH) }.thenByDescending { suffix(it) })
        .drop(KEEP)

    private const val STAMP_LENGTH = 13 // AAAAMMDD-HHMM

    private fun suffix(name: String): Int = Regex(""" \((\d+)\)\.zip$""").find(name)?.groupValues?.get(1)?.toInt() ?: 0
}
