package com.murkio.identity.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.murkio.identity.android.FlowController
import com.murkio.identity.android.FlowState
import com.murkio.identity.android.FlowStep
import com.murkio.identity.android.Murkio
import com.murkio.identity.android.MurkioClient
import com.murkio.identity.android.MurkioCredentials
import com.murkio.identity.android.MurkioException
import com.murkio.identity.android.MurkioSession
import com.murkio.identity.android.canvas.CanvasTheme
import com.murkio.identity.android.canvas.defaultCanvas
import com.murkio.identity.android.canvas.pickCanvas
import com.murkio.identity.android.internal.murkioString
import com.murkio.identity.android.internal.normalizeLocale
import com.murkio.identity.android.translateError
import kotlinx.coroutines.launch
import java.util.WeakHashMap

/**
 * MurkioFormState joins the standalone composables of one form: the inputs
 * write the values, the buttons run the flow with them, and the built-in
 * screens (email code, two-step verification, password reset) open in a
 * dialog painted with the application's canvas theme.
 *
 * Composables outside a [MurkioIdentityForm] share the default form of the
 * client, so most apps never create one. The fields are plain state: a
 * Material `OutlinedTextField(form.email, { form.email = it })` works with it
 * as well as the composables of this SDK.
 */
@Stable
public class MurkioFormState internal constructor(public val client: MurkioClient) {
    public var email: String by mutableStateOf("")
    public var password: String by mutableStateOf("")

    private var keepLoggedValue by mutableStateOf(false)
    private var keepLoggedControls by mutableIntStateOf(0)

    /**
     * "Keep me signed in". Without a [MurkioIdentityKeepLogged] in the form
     * the session is kept: that is what a user expects from an app.
     */
    public var keepLogged: Boolean
        get() = if (keepLoggedControls == 0) true else keepLoggedValue
        set(value) {
            keepLoggedValue = value
        }

    internal val flow: FlowController = FlowController(client)

    /** Called when the user is signed in. */
    public var onLogin: (MurkioSession) -> Unit = {}

    /** Called when an action fails. */
    public var onError: (MurkioException) -> Unit = {}

    internal var dialogOwner: Any? by mutableStateOf(null)

    internal var stepContainer: MurkioStepContainer by mutableStateOf(DialogStepContainer)

    /** Signs in with what the inputs hold. */
    public suspend fun login() {
        flow.login(MurkioCredentials(email, password, keepLogged))
    }

    /** Creates the account with what the inputs hold. */
    public suspend fun register() {
        flow.register(MurkioCredentials(email, password, keepLogged))
    }

    internal fun attachKeepLogged(initial: Boolean) {
        if (keepLoggedControls++ == 0) keepLoggedValue = initial
    }

    internal fun detachKeepLogged() {
        keepLoggedControls--
    }

    internal companion object {
        private val shared = WeakHashMap<MurkioClient, MurkioFormState>()

        /** The form shared by every composable that is not inside a [MurkioIdentityForm]. */
        fun shared(client: MurkioClient): MurkioFormState =
            synchronized(shared) { shared.getOrPut(client) { MurkioFormState(client) } }
    }
}

/**
 * Where the built-in screens of a form (email code, two-step verification,
 * password reset) are shown. `onDismiss` leaves the screen and goes back to
 * the form; `content` is the screen. The default is a dialog; an app can show
 * them in a bottom sheet, or in place:
 *
 * ```kotlin
 * MurkioIdentityForm(stepContainer = { onDismiss, content ->
 *     ModalBottomSheet(onDismissRequest = onDismiss) { content() }
 * }) { ... }
 * ```
 */
public typealias MurkioStepContainer = @Composable (onDismiss: () -> Unit, content: @Composable () -> Unit) -> Unit

private val DialogStepContainer: MurkioStepContainer = { onDismiss, content ->
    Dialog(onDismissRequest = onDismiss) { content() }
}

private val LocalMurkioForm = compositionLocalOf<MurkioFormState?> { null }

/** The form of the composables at this point: the nearest [MurkioIdentityForm], else the client's default form. */
@Composable
public fun currentMurkioForm(): MurkioFormState = LocalMurkioForm.current ?: MurkioFormState.shared(Murkio.client)

/**
 * Gives the composables inside it their own form, apart from the app's
 * default one. Only needed for two independent forms on screen, to use a
 * client other than the app's, to receive [onLogin] and [onError] in one
 * place, or to choose where the built-in screens show ([stepContainer]).
 */
@Composable
public fun MurkioIdentityForm(
    client: MurkioClient? = null,
    onLogin: (MurkioSession) -> Unit = {},
    onError: (MurkioException) -> Unit = {},
    stepContainer: MurkioStepContainer? = null,
    content: @Composable (MurkioFormState) -> Unit,
) {
    val murkio = client ?: Murkio.client
    val form = remember(murkio) { MurkioFormState(murkio) }
    form.onLogin = onLogin
    form.onError = onError
    form.stepContainer = stepContainer ?: DialogStepContainer
    CompositionLocalProvider(LocalMurkioForm provides form) {
        FormHost(form)
        content(form)
    }
}

/**
 * Follows the flow of a form: reports its errors and sign-ins, and shows the
 * built-in screens in a dialog. Every standalone composable calls it; the
 * first one on screen becomes the host, so there is exactly one dialog.
 */
@Composable
private fun FormHost(form: MurkioFormState) {
    val token = remember { Any() }
    DisposableEffect(form) {
        if (form.dialogOwner == null) form.dialogOwner = token
        onDispose { if (form.dialogOwner === token) form.dialogOwner = null }
    }
    if (form.dialogOwner !== token) return

    val state by form.flow.state.collectAsState()
    LaunchedEffect(state.error) { state.error?.let { form.onError(it) } }
    LaunchedEffect(state.step.name) {
        if (state.step is FlowStep.Done) {
            form.password = ""
            form.client.currentSession?.let { form.onLogin(it) }
        }
    }
    val returned by form.client.oauthReturn.collectAsState()
    LaunchedEffect(returned) { form.flow.takeOAuthReturn() }

    if (!state.inStep) return
    var theme by remember { mutableStateOf<CanvasTheme?>(null) }
    LaunchedEffect(form) {
        theme = try {
            val bootstrap = form.client.bootstrap()
            pickCanvas(bootstrap.canvasDocument, bootstrap.config).theme
        } catch (_: MurkioException) {
            defaultCanvas().theme
        }
    }
    val locale = formLocale(form, null)
    form.stepContainer({ form.flow.back() }) {
        theme?.let {
            StepView(form.flow, state, it, themeFontFamily(it, null), locale, onOpenUrl = { url -> form.client.openUrl(url) })
        }
    }
}

@Composable
private fun formLocale(form: MurkioFormState, locale: String?): String = normalizeLocale(
    locale ?: form.client.configuredLocale ?: LocalConfiguration.current.locales.get(0)?.toLanguageTag() ?: "en",
)

@Composable
private fun formState(form: MurkioFormState): FlowState = form.flow.state.collectAsState().value

/**
 * The email field of a sign-in form. It is the unstyled foundation text
 * field: give it your look with [modifier], [textStyle] and [decorationBox],
 * as with any `BasicTextField`.
 */
@Composable
public fun MurkioIdentityEmailInput(
    modifier: Modifier = Modifier,
    textStyle: TextStyle = TextStyle.Default,
    cursorBrush: Brush = SolidColor(Color.Black),
    imeAction: ImeAction = ImeAction.Next,
    decorationBox: @Composable (innerTextField: @Composable () -> Unit) -> Unit = { it() },
) {
    val form = currentMurkioForm()
    FormHost(form)
    BasicTextField(
        value = form.email,
        onValueChange = { form.email = it },
        modifier = modifier.semantics { contentType = ContentType.Username + ContentType.EmailAddress },
        singleLine = true,
        textStyle = textStyle,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Email,
            imeAction = imeAction,
        ),
        cursorBrush = cursorBrush,
        decorationBox = decorationBox,
    )
}

/**
 * The password field of a sign-in form. The keyboard's action button signs in
 * (or registers, with [newPassword]).
 *
 * @param newPassword true on a sign-up form: password managers then offer a new password, and
 *   the keyboard's action button registers instead of signing in.
 */
@Composable
public fun MurkioIdentityPasswordInput(
    modifier: Modifier = Modifier,
    textStyle: TextStyle = TextStyle.Default,
    cursorBrush: Brush = SolidColor(Color.Black),
    visible: Boolean = false,
    newPassword: Boolean = false,
    decorationBox: @Composable (innerTextField: @Composable () -> Unit) -> Unit = { it() },
) {
    val form = currentMurkioForm()
    FormHost(form)
    val scope = rememberCoroutineScope()
    BasicTextField(
        value = form.password,
        onValueChange = { form.password = it },
        modifier = modifier.semantics { contentType = if (newPassword) ContentType.NewPassword else ContentType.Password },
        singleLine = true,
        textStyle = textStyle,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { scope.launch { if (newPassword) form.register() else form.login() } }),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        cursorBrush = cursorBrush,
        decorationBox = decorationBox,
    )
}

/**
 * The "keep me signed in" choice. Unchecked, the session ends when the app is
 * closed. [content] draws it; the default is a small checkbox followed by
 * [label].
 */
@Composable
public fun MurkioIdentityKeepLogged(
    modifier: Modifier = Modifier,
    initialValue: Boolean = false,
    label: @Composable () -> Unit = {},
    content: (@Composable (checked: Boolean, onCheckedChange: (Boolean) -> Unit) -> Unit)? = null,
) {
    val form = currentMurkioForm()
    FormHost(form)
    DisposableEffect(form) {
        form.attachKeepLogged(initialValue)
        onDispose { form.detachKeepLogged() }
    }
    val checked = form.keepLogged
    if (content != null) {
        content(checked) { form.keepLogged = it }
        return
    }
    Row(
        modifier.toggleable(value = checked, role = Role.Checkbox) { form.keepLogged = it },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckBox(checked, Color(0xFF4F46E5), Color.White, Color(0xFF6B7280))
        Spacer(Modifier.width(8.dp))
        label()
    }
}

@Composable
private fun ActionButton(
    register: Boolean,
    modifier: Modifier,
    onLogin: ((MurkioSession) -> Unit)?,
    onError: ((MurkioException) -> Unit)?,
    content: @Composable (busy: Boolean) -> Unit,
) {
    val form = currentMurkioForm()
    FormHost(form)
    val scope = rememberCoroutineScope()
    val busy = formState(form).busy
    val login by rememberUpdatedState(onLogin)
    val failure by rememberUpdatedState(onError)
    Box(
        modifier.clickable(enabled = !busy, role = Role.Button) {
            login?.let { form.onLogin = it }
            failure?.let { form.onError = it }
            scope.launch { if (register) form.register() else form.login() }
        },
        contentAlignment = Alignment.Center,
    ) {
        content(busy)
    }
}

/**
 * Signs in with the email and password of its form. It only adds the click:
 * [content] draws the button (it receives `busy`, true while a request runs),
 * and [modifier] gives it your shape, color and padding.
 */
@Composable
public fun MurkioIdentityLoginButton(
    modifier: Modifier = Modifier,
    onLogin: ((MurkioSession) -> Unit)? = null,
    onError: ((MurkioException) -> Unit)? = null,
    content: @Composable (busy: Boolean) -> Unit,
) {
    ActionButton(register = false, modifier, onLogin, onError, content)
}

/**
 * Creates the account with the email and password of its form, then asks for
 * the code sent to the email.
 */
@Composable
public fun MurkioIdentityRegisterButton(
    modifier: Modifier = Modifier,
    onLogin: ((MurkioSession) -> Unit)? = null,
    onError: ((MurkioException) -> Unit)? = null,
    content: @Composable (busy: Boolean) -> Unit,
) {
    ActionButton(register = true, modifier, onLogin, onError, content)
}

/** Shows the translated error (or notice) of its form. Draws nothing when there is nothing to say. */
@Composable
public fun MurkioIdentityError(
    modifier: Modifier = Modifier,
    textStyle: TextStyle = TextStyle(color = Color(0xFFB42318)),
    locale: String? = null,
) {
    val form = currentMurkioForm()
    FormHost(form)
    val state = formState(form)
    if (state.inStep) return
    val language = formLocale(form, locale)
    val error = state.error
    val text = when {
        error != null -> translateError(error.code, language)
        state.notice != null -> murkioString(language, state.notice)
        else -> return
    }
    BasicText(text, modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = textStyle)
}

/**
 * The signed-in session as Compose state: recomposes when the user signs in
 * or out.
 *
 * ```kotlin
 * val session by rememberMurkioSession()
 * if (session == null) LoginScreen() else HomeScreen()
 * ```
 */
@Composable
public fun rememberMurkioSession(client: MurkioClient? = null): androidx.compose.runtime.State<MurkioSession?> =
    (client ?: Murkio.client).session.collectAsState()
