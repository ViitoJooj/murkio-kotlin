package com.murkio.identity.android.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.murkio.identity.android.FlowController
import com.murkio.identity.android.FlowState
import com.murkio.identity.android.FlowStep
import com.murkio.identity.android.Murkio
import com.murkio.identity.android.MurkioClient
import com.murkio.identity.android.MurkioCredentials
import com.murkio.identity.android.MurkioException
import com.murkio.identity.android.MurkioSession
import com.murkio.identity.android.PublicConfig
import com.murkio.identity.android.canvas.CanvasDocument
import com.murkio.identity.android.canvas.CanvasElement
import com.murkio.identity.android.canvas.CanvasElementType
import com.murkio.identity.android.canvas.CanvasScreen
import com.murkio.identity.android.canvas.defaultCanvas
import com.murkio.identity.android.canvas.isVisible
import com.murkio.identity.android.canvas.pickCanvas
import com.murkio.identity.android.canvas.place
import com.murkio.identity.android.canvas.submitAction
import com.murkio.identity.android.internal.Json
import com.murkio.identity.android.internal.murkioString
import com.murkio.identity.android.internal.normalizeLocale
import com.murkio.identity.android.internal.providerName
import com.murkio.identity.android.translateError
import kotlinx.coroutines.launch

/** Which screen of the canvas to draw. */
public enum class MurkioScreen { SIGN_IN, SIGN_UP }

/** Inside the frame the boxes have a fixed size, so the device's font size setting only applies up to this factor. */
private const val MAX_FONT_SCALE = 1.3f

private class Loaded(val document: CanvasDocument, val config: PublicConfig?, val failed: Boolean, val flow: FlowController?)

/**
 * Draws the application's published login canvas and runs the whole sign-in
 * on it: signup, email code, two-step verification, password reset and
 * social sign-in.
 *
 * ```kotlin
 * MurkioIdentityCanvas(onLogin = { session -> navController.navigate("home") })
 * ```
 *
 * The canvas takes the width it is given and is as tall as its layout; put it
 * in a scrolling container so the keyboard never covers it.
 *
 * @param client the client to use. Defaults to the app's one ([Murkio.client]).
 * @param locale language of the texts. Defaults to the client's option, then the device's.
 * @param preview a canvas document (JSON) to draw without the API: every element shows and the
 *   actions do nothing. For editors and screenshots.
 * @param fontFamily the app's typeface, used when the canvas theme says "inherit".
 * @param onLogin the user is signed in.
 * @param onError an action failed. The canvas already shows the translated message.
 * @param onStep a built-in screen opened (`verify_email`, `mfa`, `mfa_enroll`, `recovery_codes`,
 *   `forgot`, `reset`).
 * @param onLink a link of the canvas was tapped. Without it an https link opens in the browser
 *   and a site path (`/terms`) does nothing: an app has no site to resolve it against.
 */
@Composable
public fun MurkioIdentityCanvas(
    modifier: Modifier = Modifier,
    client: MurkioClient? = null,
    screen: MurkioScreen = MurkioScreen.SIGN_IN,
    locale: String? = null,
    preview: String? = null,
    fontFamily: FontFamily? = null,
    onLogin: (MurkioSession) -> Unit = {},
    onError: (MurkioException) -> Unit = {},
    onStep: (String) -> Unit = {},
    onLink: ((String) -> Unit)? = null,
) {
    // The client is resolved during composition: a missing setup fails here,
    // with the message that says what to add, not later in a coroutine.
    val murkio = if (preview != null) null else client ?: Murkio.client
    var loaded by remember(murkio, preview) { mutableStateOf<Loaded?>(null) }

    LaunchedEffect(murkio, preview) {
        val warn = { problems: List<String> ->
            Log.w("Murkio", "the canvas has problems; affected elements were skipped: ${problems.joinToString("; ")}")
            Unit
        }
        loaded = if (murkio == null) {
            Loaded(pickCanvas(Json.parseOrNull(preview), null, warn), null, false, null)
        } else {
            try {
                murkio.start()
                val bootstrap = murkio.bootstrap()
                Loaded(pickCanvas(bootstrap.canvasDocument, bootstrap.config, warn), bootstrap.config, false, FlowController(murkio))
            } catch (_: MurkioException) {
                Loaded(defaultCanvas(), null, true, FlowController(murkio))
            }
        }
    }

    val current = loaded ?: return
    val configuration = LocalConfiguration.current
    val language = normalizeLocale(
        locale ?: murkio?.configuredLocale ?: configuration.locales.get(0)?.toLanguageTag() ?: "en",
    )
    val family = themeFontFamily(current.document.theme, fontFamily)
    val flow = current.flow
    val state = flow?.state?.collectAsState()?.value ?: FlowState()

    if (flow != null && murkio != null) {
        val login by rememberUpdatedState(onLogin)
        val failure by rememberUpdatedState(onError)
        val stepped by rememberUpdatedState(onStep)
        LaunchedEffect(state.error) { state.error?.let { failure(it) } }
        LaunchedEffect(state.step.name) {
            if (state.inStep) stepped(state.step.name)
            if (state.step is FlowStep.Done) murkio.currentSession?.let { login(it) }
        }
        val returned by murkio.oauthReturn.collectAsState()
        LaunchedEffect(returned) { flow.takeOAuthReturn() }
    }

    if (flow != null && state.inStep) {
        StepView(flow, state, current.document.theme, family, language, onOpenUrl = { murkio?.openUrl(it) }, modifier = modifier)
        return
    }

    Frame(
        loaded = current,
        state = state,
        screen = screen,
        locale = language,
        family = family,
        oauth = murkio == null || murkio.oauthEnabled,
        onLink = { href ->
            when {
                onLink != null -> onLink(href)
                !href.startsWith("/") -> murkio?.openUrl(href)
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun Frame(
    loaded: Loaded,
    state: FlowState,
    screen: MurkioScreen,
    locale: String,
    family: FontFamily?,
    oauth: Boolean,
    onLink: (String) -> Unit,
    modifier: Modifier,
) {
    val document = loaded.document
    val theme = document.theme
    val signUp = screen == MurkioScreen.SIGN_UP && document.signUp != null
    val canvasScreen = if (signUp) document.signUp!! else document.signIn
    val flow = loaded.flow
    val scope = rememberCoroutineScope()

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<Boolean?>(null) }
    var obscure by remember { mutableStateOf(true) }
    val passwordFocus = remember { FocusRequester() }

    // The password is dropped once it was used.
    LaunchedEffect(state.step.name) { if (state.step is FlowStep.Done) password = "" }

    val keepLogged = chosen ?: keepLoggedDefault(canvasScreen, loaded.config)
    val submit = submitAction(canvasScreen, signUp, loaded.config, oauth)
    fun act(action: String, provider: String? = null) {
        if (flow == null) return
        val credentials = MurkioCredentials(email, password, keepLogged)
        scope.launch {
            when (action) {
                "login" -> flow.login(credentials)
                "register" -> flow.register(credentials)
                "forgot" -> flow.forgot(email)
                "oauth" -> flow.oauth(provider!!, keepLogged)
            }
        }
    }

    val error = state.error
    val message = when {
        error != null -> translateError(error.code, locale)
        state.notice != null -> murkioString(locale, state.notice)
        loaded.failed -> translateError("unknown", locale)
        else -> null
    }
    val isNotice = error == null && state.notice != null

    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val width = if (constraints.hasBoundedWidth) maxWidth.value else canvasScreen.desktop.width.toFloat()
        val placement = place(canvasScreen, width, loaded.config, oauth)
        val slot = placement.order.firstOrNull { it.type == CanvasElementType.ERROR_MESSAGE }
        val reading = placement.order.withIndex().associate { it.value.id to it.index }
        val density = LocalDensity.current

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CompositionLocalProvider(LocalDensity provides Density(density.density, minOf(density.fontScale, MAX_FONT_SCALE))) {
                var frame = Modifier.size(placement.frameWidth.dp, placement.frameHeight.dp).testTag("murkio-frame")
                placement.background?.let { frame = frame.background(Color(it)) }
                Box(frame) {
                    for (element in placement.paint) {
                        val rect = placement.rects.getValue(element.id)
                        key(element.id) {
                            Box(
                                Modifier
                                    .offset(rect.x.dp, rect.y.dp)
                                    .size(rect.w.dp, rect.h.dp)
                                    .testTag("murkio-el-${element.id}")
                                    .semantics {
                                        isTraversalGroup = true
                                        traversalIndex = reading.getValue(element.id).toFloat()
                                    },
                            ) {
                                val look = resolveLook(element, theme)
                                when (element.type) {
                                    CanvasElementType.EMAIL_INPUT -> {
                                        val label = element.label!!.pick(locale)
                                        MurkioTextField(
                                            value = email,
                                            onValueChange = { email = it },
                                            look = look,
                                            family = family,
                                            label = label,
                                            hint = element.placeholder?.pick(locale).orEmpty().ifEmpty { label },
                                            hintColor = Color(theme.muted),
                                            accent = Color(theme.primary),
                                            kind = FieldKind.EMAIL,
                                            modifier = Fill,
                                            tag = "murkio-input-email",
                                            imeAction = ImeAction.Next,
                                            onImeAction = { runCatching { passwordFocus.requestFocus() } },
                                        )
                                    }
                                    CanvasElementType.PASSWORD_INPUT -> {
                                        val label = element.label!!.pick(locale)
                                        MurkioTextField(
                                            value = password,
                                            onValueChange = { password = it },
                                            look = look,
                                            family = family,
                                            label = label,
                                            hint = element.placeholder?.pick(locale).orEmpty().ifEmpty { label },
                                            hintColor = Color(theme.muted),
                                            accent = Color(theme.primary),
                                            kind = if (signUp) FieldKind.NEW_PASSWORD else FieldKind.PASSWORD,
                                            modifier = Fill,
                                            tag = "murkio-input-password",
                                            focusRequester = passwordFocus,
                                            obscure = obscure,
                                            onImeAction = { act(submit) },
                                            trailing = if (!element.showToggle) {
                                                null
                                            } else {
                                                {
                                                    Box(
                                                        Modifier
                                                            .size(36.dp)
                                                            .testTag("murkio-password-toggle")
                                                            .toggleable(value = !obscure, role = Role.Switch) { obscure = !it }
                                                            .semantics {
                                                                contentDescription =
                                                                    murkioString(locale, if (obscure) "password.show" else "password.hide")
                                                            },
                                                        contentAlignment = Alignment.Center,
                                                    ) {
                                                        Eye(Color(theme.muted), crossed = !obscure)
                                                    }
                                                }
                                            },
                                        )
                                    }
                                    CanvasElementType.KEEP_LOGGED -> Row(
                                        Fill
                                            .toggleable(value = keepLogged, role = Role.Checkbox) { chosen = it }
                                            .elementBox(look),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        CheckBox(keepLogged, Color(theme.primary), Color(theme.onPrimary), Color(theme.border))
                                        Spacer(Modifier.width(8.dp))
                                        BasicText(element.label!!.pick(locale), Modifier.weight(1f), style = look.textStyle(family))
                                    }
                                    CanvasElementType.LOGIN_BUTTON, CanvasElementType.REGISTER_BUTTON -> MurkioButton(
                                        element.label!!.pick(locale), look, family,
                                        onClick = { act(if (element.type == CanvasElementType.LOGIN_BUTTON) "login" else "register") },
                                        modifier = Fill,
                                        busy = state.busy,
                                    )
                                    CanvasElementType.OAUTH_BUTTON -> MurkioButton(
                                        element.label?.pick(locale).orEmpty().ifEmpty {
                                            murkioString(locale, "oauth.continue", mapOf("provider" to providerName(element.provider!!)))
                                        },
                                        look, family,
                                        onClick = { act("oauth", element.provider) },
                                        modifier = Fill,
                                        busy = state.busy,
                                    )
                                    CanvasElementType.FORGOT_PASSWORD_LINK -> MurkioButton(
                                        element.label!!.pick(locale), look, family,
                                        onClick = { act("forgot") },
                                        modifier = Fill,
                                        busy = state.busy,
                                        underline = true,
                                    )
                                    CanvasElementType.LINK -> MurkioButton(
                                        element.text!!.pick(locale), look, family,
                                        onClick = { onLink(element.href!!) },
                                        modifier = Fill,
                                        underline = true,
                                        top = true,
                                    )
                                    CanvasElementType.ERROR_MESSAGE -> Box(Fill.elementBox(look), contentAlignment = look.alignment(top = true)) {
                                        if (element.id == slot?.id && message != null) {
                                            Message(message, look.textStyle(family).let { if (isNotice) it.copy(color = Color(theme.text)) else it })
                                        }
                                    }
                                    // Text starts at the top of its box and, like on the web, is not cut
                                    // when a font makes it a little taller than the box the editor drew.
                                    CanvasElementType.TEXT -> Box(Fill.elementBox(look), contentAlignment = look.alignment(top = true)) {
                                        BasicText(
                                            element.text!!.pick(locale),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .wrapContentHeight(Alignment.Top, unbounded = true)
                                                .then(if (element.isHeading) Modifier.semantics { heading() } else Modifier),
                                            style = look.textStyle(family),
                                        )
                                    }
                                    CanvasElementType.IMAGE -> Box(Fill.elementBox(look), contentAlignment = Alignment.Center) {
                                        CanvasImage(element.src!!, element.alt?.pick(locale)?.ifEmpty { null }, Fill)
                                    }
                                    CanvasElementType.SHAPE -> Box(Fill.elementBox(look).clearAndSetSemantics {})
                                    CanvasElementType.DIVIDER -> Row(Fill.elementBox(look), verticalAlignment = Alignment.CenterVertically) {
                                        val label = element.label?.pick(locale).orEmpty()
                                        Box(Modifier.weight(1f).height(1.dp).background(Color(theme.border)))
                                        if (label.isNotEmpty()) {
                                            BasicText(label, Modifier.padding(horizontal = 12.dp), style = look.textStyle(family))
                                            Box(Modifier.weight(1f).height(1.dp).background(Color(theme.border)))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (slot == null && message != null) {
                Box(Modifier.width(placement.frameWidth.dp).padding(top = 8.dp)) {
                    Message(
                        message,
                        TextStyle(color = Color(if (isNotice) theme.text else theme.error), fontSize = 14.sp, fontFamily = family),
                    )
                }
            }
        }
    }
}

@Composable
private fun Message(text: String, style: TextStyle) {
    BasicText(
        text,
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(Alignment.Top, unbounded = true)
            .testTag("murkio-message")
            .semantics { liveRegion = LiveRegionMode.Polite },
        style = style,
    )
}

/** The checkbox's starting value; a screen without the checkbox keeps the session, as an app is expected to. */
private fun keepLoggedDefault(screen: CanvasScreen, config: PublicConfig?): Boolean {
    val element: CanvasElement = screen.elements.firstOrNull {
        it.type == CanvasElementType.KEEP_LOGGED && isVisible(it, config, oauth = true)
    } ?: return true
    return element.defaultChecked
}
