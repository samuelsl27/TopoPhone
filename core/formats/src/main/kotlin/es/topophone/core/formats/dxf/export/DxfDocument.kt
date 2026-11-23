package es.topophone.core.formats.dxf.export

/** El sistema de las coordenadas del DXF: las del SC del proyecto o la longitud y latitud del datum (S029/RF-11, RF-12). */
enum class DxfCoordinates { LOCAL, GLOBAL }

/** Una capa del DXF con su color ACI (S029/RF-5, RF-6). */
data class DxfLayerDef(val name: String, val color: Int)

/** Lo que se escribe en la sección de entidades. [layer] es el nombre de una capa del documento. */
sealed interface DxfEntity {
    val layer: String
}

data class DxfPointEntity(override val layer: String, val x: Double, val y: Double, val z: Double) : DxfEntity

/** Un texto sin giro, con el punto de inserción en (x, y, z) y [height] en las unidades del dibujo (S029/RF-7, RF-8). */
data class DxfTextEntity(override val layer: String, val x: Double, val y: Double, val z: Double, val height: Double, val text: String) :
    DxfEntity

data class DxfVertex(val x: Double, val y: Double, val z: Double)

/** Una polilínea 3D de R12 (`POLYLINE` con `VERTEX` y `SEQEND`); [closed] para un polígono (S029/RF-3). */
data class DxfPolylineEntity(override val layer: String, val closed: Boolean, val vertices: List<DxfVertex>) : DxfEntity

/** El contenido de un DXF de exportación: sus capas, en orden, y sus entidades, en orden. */
data class DxfDocument(
    val coordinates: DxfCoordinates,
    val layers: List<DxfLayerDef>,
    val entities: List<DxfEntity>,
    /** El comentario de cabecera de un proyecto en terreno: el origen de cuadrícula y el factor (S058/RF-14); `null` sin él. */
    val comment: String? = null,
)
