package es.topophone.app.ui.receiver

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.ntrip.CasterAccess
import es.topophone.core.model.ntrip.NtripProfileDraft
import es.topophone.core.model.ntrip.NtripProfileError
import es.topophone.core.model.ntrip.PublicCaster
import es.topophone.core.model.ntrip.PublicCasters
import es.topophone.core.model.preferences.ThemeState
import es.topophone.data.ntrip.NtripProfileRepository
import es.topophone.data.ntrip.NtripSaveResult
import es.topophone.data.ntrip.PasswordResult
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val PROFILE_NAME_TAG = "ntrip_profile_name"
const val PROFILE_HOST_TAG = "ntrip_profile_host"
const val PROFILE_PORT_TAG = "ntrip_profile_port"
const val PROFILE_TLS_TAG = "ntrip_profile_tls"
const val PROFILE_USERNAME_TAG = "ntrip_profile_username"
const val PROFILE_PASSWORD_TAG = "ntrip_profile_password"
const val PROFILE_PASSWORD_TOGGLE_TAG = "ntrip_profile_password_toggle"
const val PROFILE_MOUNTPOINT_TAG = "ntrip_profile_mountpoint"
const val PROFILE_SIGNUP_TAG = "ntrip_profile_signup"
const val PROFILE_SAVE_TAG = "ntrip_profile_save"
const val CASTER_OTHER_TAG = "caster_other"

fun casterTag(id: String) = "caster_$id"

/**
 * Formulario de perfil NTRIP (S007/RF-2). [portText] es lo que se escribe; [publicCaster] el caster
 * público del que se partió, para mostrar su alta (S007/RF-12); [choosingCaster] al empezar uno nuevo.
 */
data class NtripProfileFormUiState(
    val draft: NtripProfileDraft = NtripProfileDraft(name = "", host = ""),
    val portText: String = NtripProfileDraft.DEFAULT_PORT.toString(),
    val password: String = "",
    val passwordVisible: Boolean = false,
    val loaded: Boolean = false,
    val editing: Boolean = false,
    val choosingCaster: Boolean = false,
    val publicCaster: PublicCaster? = null,
    val errors: Set<NtripProfileError> = emptySet(),
    val saved: Boolean = false,
)

class NtripProfileFormActions(
    val onChange: (NtripProfileDraft) -> Unit = {},
    val onPortChange: (String) -> Unit = {},
    val onPasswordChange: (String) -> Unit = {},
    val onTogglePassword: () -> Unit = {},
    /** `null` es "Otro caster"; [name] es el nombre del caster en la interfaz. */
    val onChooseCaster: (caster: PublicCaster?, name: String) -> Unit = { _, _ -> },
    val onSave: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onOpenUrl: (String) -> Unit = {},
)

/** Perfil nuevo, partiendo de un caster público u otro (S007/RF-11), o editado (S007/RF-2, RF-14 de S006 en estilo). */
@HiltViewModel
class NtripProfileFormViewModel @Inject constructor(private val profiles: NtripProfileRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(NtripProfileFormUiState())
    val state: StateFlow<NtripProfileFormUiState> = mutableState.asStateFlow()

    private var profileId: String? = null

    /** Contraseña con la que se abrió el perfil: si no cambia, no se vuelve a cifrar (S007/RF-4). */
    private var originalPassword: String? = null
    private var started = false

    fun start(profileId: String?) {
        if (started) return
        started = true
        this.profileId = profileId
        if (profileId == null) {
            mutableState.value = NtripProfileFormUiState(loaded = true, choosingCaster = true) // S007/RF-11
            return
        }
        viewModelScope.launch {
            val profile = profiles.profile(profileId)
            val password = (profiles.password(profileId) as? PasswordResult.Ok)?.password
            originalPassword = password
            mutableState.value = if (profile == null) {
                NtripProfileFormUiState(loaded = true)
            } else {
                NtripProfileFormUiState(
                    draft = profile.draft,
                    portText = profile.draft.port.toString(),
                    password = password.orEmpty(),
                    loaded = true,
                    editing = true,
                )
            }
        }
    }

    fun actions(onBack: () -> Unit, onOpenUrl: (String) -> Unit) = NtripProfileFormActions(
        onChange = ::update,
        onPortChange = { text -> mutableState.update { it.copy(portText = text, errors = it.errors - NtripProfileError.INVALID_PORT) } },
        onPasswordChange = { text -> mutableState.update { it.copy(password = text) } },
        onTogglePassword = { mutableState.update { it.copy(passwordVisible = !it.passwordVisible) } }, // S007/RF-5
        onChooseCaster = ::chooseCaster,
        onSave = ::save,
        onBack = onBack,
        onOpenUrl = onOpenUrl,
    )

    fun update(draft: NtripProfileDraft) = mutableState.update { it.copy(draft = draft, errors = emptySet()) }

    /** S007/RF-11 a RF-13: rellena nombre, servidor, puerto, TLS y, si es de acceso libre, usuario y contraseña. */
    fun chooseCaster(caster: PublicCaster?, name: String) = mutableState.update { current ->
        if (caster == null) {
            current.copy(choosingCaster = false)
        } else {
            current.copy(
                draft = current.draft.copy(
                    name = name,
                    host = caster.host,
                    port = caster.port,
                    useTls = caster.useTls,
                    username = caster.openUsername ?: current.draft.username,
                ),
                portText = caster.port.toString(),
                password = caster.openPassword ?: current.password,
                publicCaster = caster,
                choosingCaster = false,
            )
        }
    }

    fun save() {
        val current = mutableState.value
        val draft = current.draft.copy(port = current.portText.trim().toIntOrNull() ?: 0)
        val editingId = profileId
        viewModelScope.launch {
            val result = if (editingId == null) {
                profiles.create(draft, current.password)
            } else {
                // Sin cambios (o sin poder leer la guardada y sin escribir otra) se conserva la que hay.
                val unchanged = current.password == originalPassword || (originalPassword == null && current.password.isEmpty())
                profiles.update(editingId, draft, if (unchanged) null else current.password)
            }
            when (result) {
                is NtripSaveResult.Invalid -> mutableState.update { it.copy(errors = result.errors) } // S007/RF-3
                is NtripSaveResult.Saved -> mutableState.update { it.copy(saved = true) }
            }
        }
    }
}

/** Entrada del formulario de perfil: al guardar se vuelve a Correcciones. */
@Composable
fun NtripProfileFormRoute(
    profileId: String?,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    onBack: () -> Unit,
    viewModel: NtripProfileFormViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.start(profileId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    NtripProfileFormScreen(themeState, onToggleSun, state, viewModel.actions(onBack) { uriHandler.openUri(it) })
}

/** Formulario de perfil NTRIP (S007/RF-2): nombre, servidor, puerto, TLS, usuario, contraseña y punto de montaje. */
@Composable
fun NtripProfileFormScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: NtripProfileFormUiState,
    actions: NtripProfileFormActions,
) {
    LaunchedEffect(state.saved) { if (state.saved) actions.onBack() }
    val draft = state.draft
    SectionScaffold(
        title = stringResource(if (state.editing) R.string.ntrip_profile_edit else R.string.corrections_new_profile),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = actions.onBack,
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val nameError = state.errors.firstOrNull { it == NtripProfileError.EMPTY_NAME || it == NtripProfileError.DUPLICATE_NAME }
            FormText(draft.name, R.string.ntrip_profile_name, PROFILE_NAME_TAG, nameError) { actions.onChange(draft.copy(name = it)) }
            FormText(draft.host, R.string.ntrip_host, PROFILE_HOST_TAG, state.errors.find(NtripProfileError.EMPTY_HOST), KeyboardType.Uri) {
                actions.onChange(draft.copy(host = it))
            }
            FormText(
                state.portText,
                R.string.ntrip_port,
                PROFILE_PORT_TAG,
                state.errors.find(NtripProfileError.INVALID_PORT),
                KeyboardType.Number,
            ) {
                actions.onPortChange(it)
            }
            TlsSwitch(draft.useTls) { actions.onChange(draft.copy(useTls = it)) } // S007/RF-27
            FormText(draft.username, R.string.ntrip_username, PROFILE_USERNAME_TAG, null, KeyboardType.Ascii) {
                actions.onChange(draft.copy(username = it))
            }
            PasswordField(state.password, state.passwordVisible, actions)
            // S007/RF-21: escrito a mano, no se sabe si necesita la GGA.
            FormText(draft.mountpoint, R.string.ntrip_mountpoint, PROFILE_MOUNTPOINT_TAG, null, KeyboardType.Ascii) {
                actions.onChange(draft.copy(mountpoint = it, mountpointNeedsGga = null))
            }
            state.publicCaster?.let { caster ->
                val access = casterAccessLabel(caster.access)
                Text(
                    stringResource(R.string.ntrip_profile_signup, access, caster.signupUrl),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .heightIn(min = MinTouchTarget)
                        .clickable { actions.onOpenUrl(caster.signupUrl) }
                        .testTag(PROFILE_SIGNUP_TAG),
                ) // S007/RF-12
            }
            Button(onClick = actions.onSave, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(PROFILE_SAVE_TAG)) {
                Text(stringResource(R.string.project_save))
            }
        }
        if (state.choosingCaster) CasterChooser(actions.onChooseCaster)
    }
}

@Composable
private fun FormText(
    value: String,
    label: Int,
    tag: String,
    error: NtripProfileError?,
    keyboardType: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        isError = error != null,
        supportingText = error?.let { { Text(stringResource(profileErrorText(it))) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

/** Contraseña oculta salvo que se pulse el control para verla (S007/RF-5). */
@Composable
private fun PasswordField(password: String, visible: Boolean, actions: NtripProfileFormActions) {
    OutlinedTextField(
        value = password,
        onValueChange = actions.onPasswordChange,
        label = { Text(stringResource(R.string.ntrip_password)) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        // Visible, ya no es un campo de contraseña: TalkBack la lee (RNF-T8).
        keyboardOptions = KeyboardOptions(
            keyboardType = if (visible) KeyboardType.Ascii else KeyboardType.Password,
            autoCorrectEnabled = false,
        ),
        trailingIcon = {
            IconButton(onClick = actions.onTogglePassword, modifier = Modifier.testTag(PROFILE_PASSWORD_TOGGLE_TAG)) {
                Icon(
                    painterResource(if (visible) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                    contentDescription = stringResource(
                        if (visible) R.string.ntrip_profile_hide_password else R.string.ntrip_profile_show_password,
                    ),
                )
            }
        },
        modifier = Modifier.fillMaxWidth().testTag(PROFILE_PASSWORD_TAG),
    )
}

@Composable
private fun TlsSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .testTag(PROFILE_TLS_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(stringResource(R.string.ntrip_profile_tls))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** "Elegir caster": los públicos de España con su acceso, y "Otro caster" (S007/RF-11, RF-12). */
@Composable
private fun CasterChooser(onChoose: (PublicCaster?, String) -> Unit) {
    AlertDialog(
        onDismissRequest = { onChoose(null, "") },
        title = { Text(stringResource(R.string.caster_choose_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                PublicCasters.ALL.forEach { caster ->
                    val name = stringResource(publicCasterName(caster.id) ?: R.string.caster_other)
                    ListItem(
                        headlineContent = { Text(name) },
                        supportingContent = { Text(casterAccessLabel(caster.access)) },
                        modifier = Modifier.heightIn(min = MinTouchTarget).clickable {
                            onChoose(caster, name)
                        }.testTag(casterTag(caster.id)),
                    )
                    HorizontalDivider()
                }
                ListItem(
                    headlineContent = { Text(stringResource(R.string.caster_other)) },
                    modifier = Modifier.heightIn(min = MinTouchTarget).clickable { onChoose(null, "") }.testTag(CASTER_OTHER_TAG),
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { onChoose(null, "") }, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}

private fun Set<NtripProfileError>.find(error: NtripProfileError) = error.takeIf { it in this }

private fun profileErrorText(error: NtripProfileError): Int = when (error) {
    NtripProfileError.EMPTY_NAME -> R.string.ntrip_profile_name_empty
    NtripProfileError.DUPLICATE_NAME -> R.string.ntrip_profile_name_duplicate
    NtripProfileError.EMPTY_HOST -> R.string.ntrip_profile_host_empty
    NtripProfileError.INVALID_PORT -> R.string.ntrip_profile_port_invalid
}

@Composable
private fun casterAccessLabel(access: CasterAccess): String = stringResource(
    when (access) {
        CasterAccess.FREE_REGISTRATION -> R.string.caster_access_free_registration
        CasterAccess.REGISTRATION -> R.string.caster_access_registration
        CasterAccess.REQUEST -> R.string.caster_access_request
        CasterAccess.OPEN -> R.string.caster_access_open
        CasterAccess.PAID -> R.string.caster_access_paid
    },
)

/** Nombre en la interfaz de cada caster público (S007/RF-11); `null` si el catálogo tiene uno sin nombre. */
fun publicCasterName(id: String): Int? = when (id) {
    "ign_network" -> R.string.caster_ign_network
    "ign_station" -> R.string.caster_ign_station
    "rap_network" -> R.string.caster_rap_network
    "rap_station" -> R.string.caster_rap_station
    "aragea" -> R.string.caster_aragea
    "rgapa" -> R.string.caster_rgapa
    "xgaib" -> R.string.caster_xgaib
    "grafcan" -> R.string.caster_grafcan
    "rgac" -> R.string.caster_rgac
    "itacyl" -> R.string.caster_itacyl
    "catnet" -> R.string.caster_catnet
    "erva" -> R.string.caster_erva
    "rep_network" -> R.string.caster_rep_network
    "rep_station" -> R.string.caster_rep_station
    "larioja" -> R.string.caster_larioja
    "regam" -> R.string.caster_regam
    "rgan_2" -> R.string.caster_rgan_2
    "rgan_4" -> R.string.caster_rgan_4
    "euskadi_network" -> R.string.caster_euskadi_network
    "euskadi_station" -> R.string.caster_euskadi_station
    "bizkaia" -> R.string.caster_bizkaia
    else -> null
}
