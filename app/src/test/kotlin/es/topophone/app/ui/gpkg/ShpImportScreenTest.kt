package es.topophone.app.ui.gpkg

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.formats.gpkg.GpkgCrs
import es.topophone.core.formats.gpkg.GpkgCrsCatalog
import es.topophone.core.formats.shp.ShpEncoding
import es.topophone.core.model.cad.GpkgColumn
import es.topophone.core.model.cad.GpkgLayerInfo
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La pantalla de importar un Shapefile: el título, los problemas, las capas y los ficheros dejados fuera (S030/RF-1 a RF-7). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class ShpImportScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val columns = listOf(GpkgColumn("nombre", "TEXT"))
    private val points = GpkgLayerInfo("vertices", "geometry", "POINT", 5, 0, columns)
    private val polygons = GpkgLayerInfo("norte/parcelas", "geometry", "POLYGON", 2, 0, columns)
    private val utm30 = GpkgCrsCatalog.resolveEpsg(25830)!!

    private val config = GpkgImportUiState(
        stage = GpkgImportStage.CONFIG,
        format = ImportFormat.SHAPEFILE,
        fileName = "parcelas.zip",
        projectName = "Calle Mayor 12",
        layers = listOf(GpkgLayerRow(points, crs = utm30), GpkgLayerRow(polygons, crs = utm30)),
    )

    private fun show(state: GpkgImportUiState, actions: GpkgImportActions = GpkgImportActions()) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                GpkgImportScreen(ThemeState.DEFAULT, {}, state, actions, onBack = {})
            }
        }
    }

    @Test
    fun `S030 RF-1 mientras se leen los ficheros el titulo es el del Shapefile`() {
        show(GpkgImportUiState(format = ImportFormat.SHAPEFILE, fileName = "parcelas.zip"))

        compose.onNodeWithTag(GPKG_LOADING_TAG).assertIsDisplayed()
        compose.onNodeWithText("Importar Shapefile").assertIsDisplayed()
    }

    @Test
    fun `S030 RF-3 y RF-4 cada problema de lo elegido se explica`() {
        val texts = mapOf(
            GpkgFileProblem.EMPTY to "El fichero está vacío.",
            GpkgFileProblem.NOT_SHAPEFILE to "Lo elegido no es un Shapefile. Elige un ZIP con sus ficheros, o el .shp junto con los demás.",
            GpkgFileProblem.NO_SHP to "Falta el fichero .shp. Elige también el .shp junto a sus ficheros, o el ZIP entero.",
            GpkgFileProblem.MIXED_SELECTION to "Elige un solo ZIP o varios ficheros sueltos, pero no los dos a la vez.",
            GpkgFileProblem.DAMAGED to "El ZIP está dañado y no se puede leer.",
            GpkgFileProblem.TOO_BIG to "El fichero supera el límite de 100 MB.",
            GpkgFileProblem.NO_SPACE to "No hay espacio libre en el móvil para leer el fichero.",
            GpkgFileProblem.UNREADABLE to "No se ha podido leer el fichero.",
        )
        val shown = mutableStateOf(GpkgFileProblem.EMPTY)
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                GpkgImportScreen(
                    ThemeState.DEFAULT,
                    {},
                    GpkgImportUiState(
                        stage = GpkgImportStage.PROBLEM,
                        format = ImportFormat.SHAPEFILE,
                        fileName = "x.zip",
                        problem = shown.value,
                    ),
                    GpkgImportActions(),
                    onBack = {},
                )
            }
        }

        for ((problem, text) in texts) {
            shown.value = problem
            compose.waitForIdle()
            compose.onNodeWithTag(GPKG_PROBLEM_TAG).assertIsDisplayed()
            compose.onNode(hasText(text)).assertIsDisplayed()
        }
        compose.onNodeWithText("Cerrar").assertIsDisplayed()
    }

    @Test
    fun `S030 RF-7 la lista da el nombre, el tipo y el numero de objetos de cada capa marcable`() {
        show(config)

        compose.onNodeWithText("Importar Shapefile").assertIsDisplayed()
        compose.onNodeWithText("parcelas.zip").assertIsDisplayed()
        compose.onNodeWithTag(gpkgLayerTag("vertices")).assertIsDisplayed()
        compose.onNodeWithText("norte/parcelas").assertExists()
        compose.onNodeWithText("Puntos · 5 objetos").assertExists()
        compose.onNodeWithText("Polígonos · 2 objetos").assertExists()
    }

    @Test
    fun `S030 RF-5 los ficheros sin su shp se avisan con su nombre`() {
        show(config.copy(leftOutTables = listOf("solo.dbf", "solo.prj")))

        compose.onNodeWithTag(GPKG_LEFT_OUT_TAG).assertIsDisplayed()
        compose.onNodeWithText("Se dejan fuera 2 ficheros sin su .shp: solo.dbf, solo.prj.").assertIsDisplayed()
    }

    @Test
    fun `S048 RF-6 en un GeoPackage el aviso sigue siendo el de las tablas`() {
        show(config.copy(format = ImportFormat.GEOPACKAGE, leftOutTables = listOf("censo")))

        compose.onNodeWithText("Se deja fuera 1 tabla sin geometría o de teselas: censo.").assertIsDisplayed()
    }

    @Test
    fun `S030 RF-9 una capa sin prj o con un sistema que la app no ofrece pide elegir uno`() {
        val unavailable = GpkgCrs.Unavailable("PRJ", 0, "ETRS_1989_UTM_Zone_33N")
        show(
            config.copy(
                layers = listOf(
                    GpkgLayerRow(points, crs = GpkgCrs.Undeclared),
                    GpkgLayerRow(polygons, crs = unavailable),
                ),
            ),
        )

        compose.onNodeWithText("La capa no tiene .prj o no se entiende: elige su sistema de coordenadas.").assertExists()
        compose.onNodeWithText("El sistema ETRS_1989_UTM_Zone_33N del .prj no está disponible en la app: elige uno.").assertExists()
    }

    @Test
    fun `S030 RF-8 con un sistema reconocido se ve su nombre y su unidad`() {
        show(config.copy(layers = listOf(GpkgLayerRow(points, crs = utm30))))

        compose.onNodeWithTag(gpkgCrsTag("vertices")).assertIsDisplayed()
        compose.onNodeWithTag(gpkgUnitTag("vertices")).assertIsDisplayed()
    }

    @Test
    fun `S030 RF-18 una capa con dbf ofrece la codificacion y avisa de un cpg desconocido`() {
        val chosen = mutableListOf<Pair<String, ShpEncoding>>()
        show(
            config.copy(
                layers = listOf(
                    GpkgLayerRow(points, crs = utm30, encoding = ShpEncoding.UTF_8),
                    GpkgLayerRow(polygons, crs = utm30, encoding = ShpEncoding.WINDOWS_1252, cpgUnknown = true),
                ),
            ),
            GpkgImportActions(onEncoding = { table, encoding -> chosen += table to encoding }),
        )

        compose.onNodeWithTag(shpEncodingTag("vertices", ShpEncoding.UTF_8)).assertExists()
        compose.onNodeWithTag(shpEncodingTag("vertices", ShpEncoding.WINDOWS_1252)).performScrollTo().performClick()
        compose.onNodeWithTag(shpCpgUnknownTag("norte/parcelas")).assertExists()
        compose.onNodeWithTag(shpCpgUnknownTag("vertices")).assertDoesNotExist()

        chosen shouldBe listOf("vertices" to ShpEncoding.WINDOWS_1252)
    }

    @Test
    fun `S030 RF-18 una capa sin dbf no ofrece codificacion`() {
        show(config.copy(layers = listOf(GpkgLayerRow(points, crs = utm30))))

        compose.onNodeWithTag(shpEncodingTag("vertices", ShpEncoding.UTF_8)).assertDoesNotExist()
    }
}
