package es.topophone.app.ui.collector

import androidx.lifecycle.SavedStateHandle
import es.topophone.core.model.codes.AttributeError
import es.topophone.core.model.codes.AttributeForm
import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeAttribute
import es.topophone.core.model.codes.CodeKey
import es.topophone.core.model.codes.CodeSelector
import es.topophone.core.model.codes.PointCode
import es.topophone.core.model.codes.ProjectCodes
import es.topophone.core.model.codes.RecentCodes
import es.topophone.core.model.codes.SurveyCode
import es.topophone.data.codes.CodeLibraryRepository
import es.topophone.data.point.PointRepository
import java.text.DecimalFormatSymbols
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** De dónde saca el recolector los códigos del proyecto y los recientes (S024/RF-1). */
interface CollectorCodeSource {
    /** Los códigos de geometría Punto del proyecto: los de la biblioteca vinculada y los creados en él (S024/RF-1). */
    fun available(projectId: String): Flow<List<SurveyCode>>

    /** Los códigos usados por los puntos del proyecto, del más reciente al más antiguo (S024/RF-1). */
    fun recent(projectId: String): Flow<List<String>>

    /** Los ids de los códigos creados en el proyecto, los únicos que se editan desde el recolector (S024/RF-7). */
    fun ownIds(projectId: String): Flow<Set<String>> = flowOf(emptySet())

    companion object {
        val NONE = object : CollectorCodeSource {
            override fun available(projectId: String): Flow<List<SurveyCode>> = flowOf(emptyList())

            override fun recent(projectId: String): Flow<List<String>> = flowOf(emptyList())
        }
    }
}

class RoomCollectorCodeSource @Inject constructor(private val codes: CodeLibraryRepository, private val points: PointRepository) :
    CollectorCodeSource {
    override fun available(projectId: String): Flow<List<SurveyCode>> =
        combine(codes.projectCopy(projectId), codes.projectCodes(projectId)) { copy, own ->
            ProjectCodes.available(copy?.codes.orEmpty(), own)
        }

    override fun recent(projectId: String): Flow<List<String>> = points.recentCodes(projectId)

    override fun ownIds(projectId: String): Flow<Set<String>> = codes.projectCodes(projectId).map { list ->
        list.mapTo(HashSet()) { it.id }
    }
}

/** Un campo del formulario de atributos del recolector (S024/RF-8, RF-9). */
data class AttributeFieldUi(
    val name: String,
    val type: AttributeType,
    val options: List<String>,
    val text: String,
    val error: AttributeError?,
)

/**
 * Lo que muestra el recolector del código del punto (S024/RF-1 a RF-4, RF-8, RF-9): el código elegido, el selector con sus recientes y
 * el formulario de atributos. [options] ya va filtrado por [query] y con los [recentCount] primeros si son recientes.
 */
data class CollectorCodeUi(
    val selected: SurveyCode? = null,
    val query: String = "",
    val options: List<SurveyCode> = emptyList(),
    val recentCount: Int = 0,
    val totalCodes: Int = 0,
    /** Los códigos creados en el proyecto, que se pueden editar y borrar (S024/RF-7). */
    val ownIds: Set<String> = emptySet(),
    val fields: List<AttributeFieldUi> = emptyList(),
) {
    /** Todos los atributos tienen un valor válido; sin código no hay atributos (S024/RF-9). */
    val valid: Boolean get() = fields.all { it.error == null }
}

/** El signo decimal de la interfaz (S024/RF-8, RNF-T9). */
internal fun interfaceDecimal(): Char = DecimalFormatSymbols.getInstance(Locale.getDefault()).decimalSeparator

/**
 * El código y los atributos del punto que se va a medir (S024). El código elegido se conserva de un punto al siguiente; los valores de
 * los atributos vuelven a los por defecto del código al guardar un punto y no se arrastran (decisión de la entrevista).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CollectorCodes(
    scope: CoroutineScope,
    source: CollectorCodeSource,
    projectId: Flow<String?>,
    private val handle: SavedStateHandle = SavedStateHandle(),
    private val decimal: () -> Char = ::interfaceDecimal,
) {
    private val selectedKey = MutableStateFlow(handle.get<String>(KEY))
    private val query = MutableStateFlow("")

    /** Los atributos para los que se escribieron los textos, y los textos; si el código cambia de atributos, se vuelve a los por defecto. */
    private val typed = MutableStateFlow<Pair<List<CodeAttribute>, List<String>>?>(null)

    private val available = projectId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else source.available(id) }
    private val recent = projectId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else source.recent(id) }
    private val own = projectId.flatMapLatest { id -> if (id == null) flowOf(emptySet()) else source.ownIds(id) }

    private class Choices(val available: List<SurveyCode>, val selector: CodeSelector, val recentCount: Int)

    private val choices: Flow<Choices> = combine(available, recent) { available, recent ->
        Choices(available, CodeSelector(available, recent), RecentCodes.resolve(recent, available).size)
    }

    val ui: StateFlow<CollectorCodeUi> = combine(choices, selectedKey, query, typed, own) { choices, key, query, typed, own ->
        val selected = key?.let { k -> choices.available.firstOrNull { CodeKey.of(it.code) == k } }
        val texts = selected?.let { s ->
            typed?.takeIf { it.first == s.attributes }?.second
                ?: AttributeForm.initial(s.attributes, decimal())
        }
        val result = selected?.let { AttributeForm.validate(it.attributes, texts.orEmpty(), decimal()) }
        CollectorCodeUi(
            selected = selected,
            query = query,
            options = choices.selector.filter(query),
            recentCount = if (CodeKey.of(query).isEmpty()) choices.recentCount else 0,
            totalCodes = choices.available.size,
            ownIds = own,
            fields = selected?.attributes.orEmpty().mapIndexed { i, a ->
                AttributeFieldUi(a.name, a.type, a.options, texts.orEmpty().getOrElse(i) { "" }, result?.errors?.getOrNull(i))
            },
        )
    }.stateIn(scope, SharingStarted.Eagerly, CollectorCodeUi())

    /** Elige el código del siguiente punto, o ninguno (S024/RF-3); los atributos vuelven a sus valores por defecto. */
    fun select(code: SurveyCode?) {
        val key = code?.let { CodeKey.of(it.code) }
        selectedKey.value = key
        handle[KEY] = key
        typed.value = null
        query.value = ""
    }

    fun setQuery(text: String) {
        query.value = text
    }

    fun setAttribute(index: Int, text: String) {
        val current = ui.value
        val selected = current.selected ?: return
        if (index !in selected.attributes.indices) return
        val texts = current.fields.map { it.text }.toMutableList()
        texts[index] = text
        typed.value = selected.attributes to texts
    }

    /** El punto ya se guardó: los atributos vuelven a los por defecto y el código se conserva (S024/RF-3). */
    fun afterSave() {
        typed.update { null }
    }

    /** El código con sus valores tal como se guardarán con el punto, o null sin código o con algún valor no válido (S024/RF-10). */
    fun pointCode(): PointCode? {
        val current = ui.value
        val selected = current.selected ?: return null
        val values = AttributeForm.validate(selected.attributes, current.fields.map { it.text }, decimal())
        if (!values.isValid) return null
        return PointCode(selected.code, selected.description, selected.color, selected.symbol, values.values)
    }

    private companion object {
        const val KEY = "collector_code"
    }
}
