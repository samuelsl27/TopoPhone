package es.topophone.core.formats.shp.export

import es.topophone.core.formats.csv.AttributeColumn
import es.topophone.core.formats.csv.ExportColumn
import es.topophone.core.model.codes.AttributeType
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Los nombres de las columnas del `.dbf`: los de la tabla fija y los de los atributos de los códigos (S055/RF-8, RF-9). */
class ShpColumnNamesTest {

    private fun attribute(name: String, type: AttributeType = AttributeType.TEXT) = AttributeColumn(name, name.lowercase(), type)

    private fun names(language: ShpNameLanguage) = ExportColumn.entries.map { ShpColumnNames.of(it, language) }

    @Test
    fun `S055 RF-8 cada columna de Todas las columnas tiene nombre en espanol y en ingles de hasta 10 caracteres en mayusculas ASCII`() {
        for (language in ShpNameLanguage.entries) {
            val all = names(language)
            all.size shouldBe ExportColumn.entries.size
            for (name in all) {
                (name.length in 1..ShpColumnNames.MAX_LENGTH) shouldBe true
                name.all { it in 'A'..'Z' || it in '0'..'9' || it == '_' } shouldBe true
            }
        }
    }

    @Test
    fun `S055 RF-8 no hay dos columnas fijas con el mismo nombre en un idioma`() {
        for (language in ShpNameLanguage.entries) {
            val all = names(language)
            all.toSet().size shouldBe all.size
        }
    }

    @Test
    fun `S055 RF-8 la tabla fija da los nombres del plan`() {
        ShpColumnNames.of(ExportColumn.NAME, ShpNameLanguage.ES) shouldBe "NOMBRE"
        ShpColumnNames.of(ExportColumn.NAME, ShpNameLanguage.EN) shouldBe "NAME"
        ShpColumnNames.of(ExportColumn.ELEVATION, ShpNameLanguage.ES) shouldBe "COTA"
        ShpColumnNames.of(ExportColumn.ELEVATION, ShpNameLanguage.EN) shouldBe "ELEVATION"
        ShpColumnNames.of(ExportColumn.ELLIPSOIDAL_HEIGHT, ShpNameLanguage.ES) shouldBe "ALT_ELIP"
        ShpColumnNames.of(ExportColumn.MEASURED_FROM, ShpNameLanguage.ES) shouldBe "MED_INICIO"
        ShpColumnNames.of(ExportColumn.TILT_VERTICAL_ACCURACY, ShpNameLanguage.EN) shouldBe "TILT_ACC_V"
        ShpColumnNames.of(ExportColumn.CODE, ShpNameLanguage.ES) shouldBe "CODIGO"
        ShpColumnNames.of(ExportColumn.CODE, ShpNameLanguage.EN) shouldBe "CODE"
    }

    @Test
    fun `S043 RF-20 las tres columnas del receptor se llaman RX_MODEL, RX_SERIAL y RX_FW en los dos idiomas`() {
        for (language in ShpNameLanguage.entries) {
            ShpColumnNames.of(ExportColumn.EQUIPMENT_MODEL, language) shouldBe "RX_MODEL"
            ShpColumnNames.of(ExportColumn.EQUIPMENT_SERIAL, language) shouldBe "RX_SERIAL"
            ShpColumnNames.of(ExportColumn.EQUIPMENT_FIRMWARE, language) shouldBe "RX_FW"
        }
    }

    @Test
    fun `S055 RF-8 las dos columnas de replanteo que un recorte a 10 caracteres confundiria tienen nombres distintos`() {
        // «Stake delta northing» y «Stake delta easting» empezarían igual con las 10 primeras letras
        ShpColumnNames.of(ExportColumn.STAKE_D_NORTH, ShpNameLanguage.ES) shouldBe "REPL_DN"
        ShpColumnNames.of(ExportColumn.STAKE_D_EAST, ShpNameLanguage.ES) shouldBe "REPL_DE"
        ShpColumnNames.of(ExportColumn.STAKE_D_NORTH, ShpNameLanguage.EN) shouldBe "STK_DN"
        ShpColumnNames.of(ExportColumn.STAKE_D_EAST, ShpNameLanguage.EN) shouldBe "STK_DE"
    }

    @Test
    fun `S055 RF-7 las capas de lineas y poligonos llevan nombre, codigo y vertices en el idioma de la interfaz`() {
        ShpColumnNames.fixedLineNames(ShpNameLanguage.ES) shouldBe listOf("NOMBRE", "CODIGO", "VERTICES")
        ShpColumnNames.fixedLineNames(ShpNameLanguage.EN) shouldBe listOf("NAME", "CODE", "VERTICES")
    }

    @Test
    fun `S055 RF-9 un atributo se escribe en mayusculas sin acentos ni enes y con guion bajo en lugar de lo que no sea letra o cifra`() {
        val result = ShpColumnNames.forAttributes(
            listOf(attribute("Ancho vía"), attribute("Diámetro"), attribute("Año"), attribute("a-b c"), attribute("cota")),
            emptyList(),
        )

        result.names shouldBe listOf("ANCHO_VIA", "DIAMETRO", "ANO", "A_B_C", "COTA")
    }

    @Test
    fun `S055 RF-9 un nombre de mas de 10 caracteres se recorta a 10`() {
        ShpColumnNames.forAttributes(listOf(attribute("Altura del árbol")), emptyList()).names shouldBe listOf("ALTURA_DEL")
    }

    @Test
    fun `S055 RF-9 un atributo que coincide con una columna fija se distingue con un numero al final`() {
        val es = ShpColumnNames.forAttributes(listOf(attribute("cota")), ShpColumnNames.fixedPointNames(ShpNameLanguage.ES))
        val fixedEn = ShpColumnNames.fixedPointNames(ShpNameLanguage.EN)
        val en = ShpColumnNames.forAttributes(listOf(attribute("cota"), attribute("Name")), fixedEn)

        es.names shouldBe listOf("COTA1")
        en.names shouldBe listOf("COTA", "NAME1") // en inglés «COTA» no es una columna fija
    }

    @Test
    fun `S055 RF-9 dos atributos que se abrevian igual se distinguen con un numero y el nombre sigue en 10 caracteres`() {
        val result = ShpColumnNames.forAttributes(
            listOf(attribute("Altura del árbol"), attribute("Altura del poste"), attribute("Altura del muro")),
            emptyList(),
        )

        result.names shouldBe listOf("ALTURA_DEL", "ALTURA_DE1", "ALTURA_DE2")
    }

    @Test
    fun `S055 RF-9 con muchos atributos iguales el numero pasa a dos cifras y ningun nombre se repite ni pasa de 10`() {
        val attributes = (1..25).map { attribute("Medida de la parcela $it") }
        val result = ShpColumnNames.forAttributes(attributes, emptyList())

        result.names.toSet().size shouldBe 25
        result.names.all { it.length <= ShpColumnNames.MAX_LENGTH } shouldBe true
        result.names[0] shouldBe "MEDIDA_DE_"
        result.names[1] shouldBe "MEDIDA_DE1"
        result.names[10] shouldBe "MEDIDA_D10"
    }

    @Test
    fun `S055 RF-9 un atributo con el mismo nombre y otro tipo lleva su cabecera con el tipo y tambien se abrevia sin repetirse`() {
        val result = ShpColumnNames.forAttributes(
            listOf(attribute("ancho"), attribute("ancho (number)", AttributeType.NUMBER)),
            emptyList(),
        )

        result.names shouldBe listOf("ANCHO", "ANCHO__NUM")
    }

    @Test
    fun `S055 RF-9 el resumen lista los atributos cuyo nombre cambia y no los que solo pasan a mayusculas`() {
        val result = ShpColumnNames.forAttributes(
            listOf(attribute("Ancho"), attribute("Ancho vía"), attribute("cota")),
            ShpColumnNames.fixedPointNames(ShpNameLanguage.ES),
        )

        result.names shouldBe listOf("ANCHO", "ANCHO_VIA", "COTA1")
        result.renamed shouldBe listOf(ShpRenamedColumn("Ancho vía", "ANCHO_VIA"), ShpRenamedColumn("cota", "COTA1"))
    }

    @Test
    fun `S055 RF-9 sin atributos no hay nombres ni cambios`() {
        ShpColumnNames.forAttributes(emptyList(), ShpColumnNames.fixedPointNames(ShpNameLanguage.ES)) shouldBe
            ShpAttributeNames(emptyList(), emptyList())
    }

    @Test
    fun `S055 RF-9 un nombre sin letras ni cifras no queda vacio`() {
        ShpColumnNames.forAttributes(listOf(attribute("€€")), emptyList()).names shouldBe listOf("__")
    }
}
