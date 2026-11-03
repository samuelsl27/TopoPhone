package es.topophone.app.ui.codes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.AttributeValues
import es.topophone.core.model.codes.CodeAttribute
import es.topophone.core.model.codes.CodeError
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodePalette
import es.topophone.core.model.codes.SurveyCode
import es.topophone.data.codes.CodeLibraryRepository
import es.topophone.data.codes.CodeSaveResult
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Un atributo tal como se está escribiendo: el valor por defecto sin interpretar hasta guardar (S023/RF-9). */
data class AttributeDraft(
    val name: String = "",
    val type: AttributeType = AttributeType.TEXT,
    val default: String = "",
    val options: List<String> = emptyList(),
)

data class CodeEditorUiState(
    val loaded: Boolean = false,
    /** La biblioteca o el código ya no existen, o no se puede editar la biblioteca (por defecto o copia de un proyecto). */
    val available: Boolean = true,
    val isNew: Boolean = true,
    val code: String = "",
    val description: String = "",
    val geometry: CodeGeometry = CodeGeometry.POINT,
    /** null: sin color elegido, que toma el de la geometría (S023/RF-8). */
    val color: Int? = null,
    val attributes: List<AttributeDraft> = emptyList(),
    val error: CodeError? = null,
    val confirmDelete: Boolean = false,
    /** Se guardó o se borró: la pantalla se cierra. */
    val finished: Boolean = false,
) {
    val effectiveColor: Int get() = color ?: CodePalette.defaultFor(geometry)
}

/** Alta y edición de un código con sus atributos (S023/RF-5 a RF-10). */
@HiltViewModel
class CodeEditorViewModel @Inject constructor(private val repository: CodeLibraryRepository) : ViewModel() {
    private val _state = MutableStateFlow(CodeEditorUiState())
    val state: StateFlow<CodeEditorUiState> = _state

    private var libraryId = ""
    private var codeId: String? = null

    fun open(libraryId: String, codeId: String?) {
        if (this.libraryId == libraryId && this.codeId == codeId && _state.value.loaded) return
        this.libraryId = libraryId
        this.codeId = codeId
        viewModelScope.launch {
            val library = repository.library(libraryId).first()
            val code = codeId?.let { id -> library?.codes?.firstOrNull { it.id == id } }
            _state.value = when {
                library == null || library.isDefault || (codeId != null && code == null) ->
                    CodeEditorUiState(loaded = true, available = false)
                code == null -> CodeEditorUiState(loaded = true)
                else -> CodeEditorUiState(
                    loaded = true,
                    isNew = false,
                    code = code.code,
                    description = code.description,
                    geometry = code.geometry,
                    color = code.color,
                    attributes = code.attributes.map { AttributeDraft(it.name, it.type, it.defaultValue.orEmpty(), it.options) },
                )
            }
        }
    }

    fun setCode(text: String) = edit { copy(code = text) }

    fun setDescription(text: String) = edit { copy(description = text) }

    fun setGeometry(geometry: CodeGeometry) = edit { copy(geometry = geometry) }

    /** null vuelve al color por defecto de la geometría (RF-8). */
    fun setColor(color: Int?) = edit { copy(color = color) }

    fun addAttribute() = edit { copy(attributes = attributes + AttributeDraft()) }

    fun removeAttribute(index: Int) = edit { copy(attributes = attributes.filterIndexed { i, _ -> i != index }) }

    /** Sube (-1) o baja (+1) un atributo (RF-10). */
    fun moveAttribute(index: Int, delta: Int) = edit {
        val target = index + delta
        if (index !in attributes.indices || target !in attributes.indices) {
            this
        } else {
            copy(attributes = attributes.toMutableList().also { it.add(target, it.removeAt(index)) })
        }
    }

    fun setAttributeName(index: Int, name: String) = editAttribute(index) { copy(name = name) }

    /** Cambiar el tipo borra el valor por defecto, que ya no tiene por qué valer, y las opciones si deja de ser una Lista. */
    fun setAttributeType(index: Int, type: AttributeType) = editAttribute(index) {
        if (type == this.type) this else copy(type = type, default = "", options = if (type == AttributeType.LIST) options else emptyList())
    }

    fun setAttributeDefault(index: Int, text: String) = editAttribute(index) { copy(default = text) }

    fun addOption(index: Int) = editAttribute(index) { copy(options = options + "") }

    /** Si la opción cambiada era el valor por defecto, este la sigue. */
    fun setOption(index: Int, option: Int, value: String) = editAttribute(index) {
        val old = options.getOrNull(option) ?: return@editAttribute this
        copy(options = options.toMutableList().also { it[option] = value }, default = if (default == old) value else default)
    }

    fun removeOption(index: Int, option: Int) = editAttribute(index) {
        val old = options.getOrNull(option) ?: return@editAttribute this
        copy(options = options.filterIndexed { i, _ -> i != option }, default = if (default == old) "" else default)
    }

    /** Guarda en una sola transacción; si algo no vale, no se guarda nada y el motivo queda en [CodeEditorUiState.error] (RF-5 a RF-7, RF-9). */
    fun save() {
        val current = _state.value
        viewModelScope.launch {
            val code = SurveyCode(
                id = codeId.orEmpty(),
                code = current.code,
                description = current.description.trim(),
                geometry = current.geometry,
                color = current.effectiveColor,
                attributes = current.attributes.map { it.toAttribute() },
            )
            when (val result = repository.saveCode(libraryId, code)) {
                is CodeSaveResult.Saved -> _state.update { it.copy(error = null, finished = true) }
                is CodeSaveResult.Invalid -> _state.update { it.copy(error = result.error) }
                else -> _state.update { it.copy(available = false) }
            }
        }
    }

    fun askDelete() = edit { copy(confirmDelete = true) }

    fun cancelDelete() = edit { copy(confirmDelete = false) }

    fun confirmDelete() {
        val id = codeId ?: return
        viewModelScope.launch {
            repository.deleteCode(libraryId, id)
            _state.update { it.copy(confirmDelete = false, finished = true) }
        }
    }

    private inline fun edit(change: CodeEditorUiState.() -> CodeEditorUiState) {
        _state.update { it.change().copy(error = null) }
    }

    private inline fun editAttribute(index: Int, change: AttributeDraft.() -> AttributeDraft) = edit {
        if (index !in attributes.indices) this else copy(attributes = attributes.toMutableList().also { it[index] = it[index].change() })
    }

    private fun AttributeDraft.toAttribute(): CodeAttribute {
        val raw = default.trim()
        val value = when {
            raw.isEmpty() -> null
            type == AttributeType.NUMBER ->
                // El usuario puede escribir el signo decimal de su país: vale el punto o la coma (solo uno, sin miles).
                AttributeValues.canonical(type, raw, '.') ?: AttributeValues.canonical(type, raw, ',') ?: raw
            type == AttributeType.INTEGER -> AttributeValues.canonical(type, raw, '.') ?: raw
            else -> raw
        }
        return CodeAttribute(name.trim(), type, value, if (type == AttributeType.LIST) options.map { it.trim() } else emptyList())
    }
}
