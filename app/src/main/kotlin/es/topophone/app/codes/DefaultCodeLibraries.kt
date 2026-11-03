package es.topophone.app.codes

import android.content.Context
import androidx.annotation.StringRes
import es.topophone.app.R
import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeAttribute
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeLibrary
import es.topophone.core.model.codes.CodePalette
import es.topophone.core.model.codes.SurveyCode
import es.topophone.data.codes.DefaultLibrarySource

/**
 * Las dos bibliotecas que vienen con la app (S023/RF-3, plan D3): «Topografía general» y «Urbanización». Los códigos son los mismos en
 * todos los idiomas; sus descripciones y los nombres de los atributos salen de `strings.xml` en el idioma de la app en ese momento.
 */
class DefaultCodeLibraries(private val context: Context) : DefaultLibrarySource {
    private class Def(val code: String, @StringRes val description: Int, val geometry: CodeGeometry, val withMaterial: Boolean = false)

    override fun libraries(): List<CodeLibrary> = listOf(
        build(GENERAL_ID, R.string.default_library_general, GENERAL),
        build(URBAN_ID, R.string.default_library_urban, URBAN),
    )

    private fun build(id: String, @StringRes name: Int, defs: List<Def>): CodeLibrary = CodeLibrary(
        id = id,
        name = context.getString(name),
        isDefault = true,
        codes = defs.mapIndexed { index, def ->
            SurveyCode(
                id = "$id:${def.code}",
                code = def.code,
                description = context.getString(def.description),
                geometry = def.geometry,
                color = CodePalette.COLORS[index % CodePalette.COLORS.size],
                attributes = if (def.withMaterial) attributes() else emptyList(),
            )
        },
    )

    private fun attributes() = listOf(
        CodeAttribute(
            context.getString(R.string.default_attr_material),
            AttributeType.LIST,
            options = listOf(
                R.string.default_opt_concrete,
                R.string.default_opt_pvc,
                R.string.default_opt_cast_iron,
                R.string.default_opt_brick,
                R.string.default_opt_other,
            ).map(context::getString),
        ),
        CodeAttribute(context.getString(R.string.default_attr_diameter), AttributeType.NUMBER),
    )

    companion object {
        const val GENERAL_ID = "default:general"
        const val URBAN_ID = "default:urbanizacion"

        private val GENERAL = listOf(
            Def("VER", R.string.default_code_general_ver, CodeGeometry.POINT),
            Def("BASE", R.string.default_code_general_base, CodeGeometry.POINT),
            Def("EST", R.string.default_code_general_est, CodeGeometry.POINT),
            Def("CTL", R.string.default_code_general_ctl, CodeGeometry.POINT),
            Def("TER", R.string.default_code_general_ter, CodeGeometry.POINT),
            Def("ARB", R.string.default_code_general_arb, CodeGeometry.POINT),
            Def("POS", R.string.default_code_general_pos, CodeGeometry.POINT),
            Def("PIE", R.string.default_code_general_pie, CodeGeometry.LINE),
            Def("CAB", R.string.default_code_general_cab, CodeGeometry.LINE),
            Def("CAM", R.string.default_code_general_cam, CodeGeometry.LINE),
            Def("VAL", R.string.default_code_general_val, CodeGeometry.LINE),
            Def("ARR", R.string.default_code_general_arr, CodeGeometry.LINE),
            Def("EDI", R.string.default_code_general_edi, CodeGeometry.POLYGON),
            Def("PAR", R.string.default_code_general_par, CodeGeometry.POLYGON),
            Def("BAL", R.string.default_code_general_bal, CodeGeometry.POLYGON),
        )

        private val URBAN = listOf(
            Def("ARQ", R.string.default_code_urban_arq, CodeGeometry.POINT, withMaterial = true),
            Def("POZ", R.string.default_code_urban_poz, CodeGeometry.POINT),
            Def("SUM", R.string.default_code_urban_sum, CodeGeometry.POINT),
            Def("FAR", R.string.default_code_urban_far, CodeGeometry.POINT),
            Def("SEM", R.string.default_code_urban_sem, CodeGeometry.POINT),
            Def("HID", R.string.default_code_urban_hid, CodeGeometry.POINT),
            Def("VAD", R.string.default_code_urban_vad, CodeGeometry.POINT),
            Def("BOR", R.string.default_code_urban_bor, CodeGeometry.LINE),
            Def("ACE", R.string.default_code_urban_ace, CodeGeometry.LINE),
            Def("CAL", R.string.default_code_urban_cal, CodeGeometry.LINE),
            Def("EJE", R.string.default_code_urban_eje, CodeGeometry.LINE),
            Def("COL", R.string.default_code_urban_col, CodeGeometry.LINE, withMaterial = true),
            Def("MUR", R.string.default_code_urban_mur, CodeGeometry.LINE),
            Def("ZVE", R.string.default_code_urban_zve, CodeGeometry.POLYGON),
            Def("ESC", R.string.default_code_urban_esc, CodeGeometry.POLYGON),
        )
    }
}
