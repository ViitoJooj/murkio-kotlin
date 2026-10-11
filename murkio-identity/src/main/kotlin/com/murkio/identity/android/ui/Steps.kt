package com.murkio.identity.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.murkio.identity.android.FlowController
import com.murkio.identity.android.FlowState
import com.murkio.identity.android.FlowStep
import com.murkio.identity.android.canvas.CanvasTheme
import com.murkio.identity.android.internal.murkioString
import com.murkio.identity.android.translateError
import kotlinx.coroutines.launch

/**
 * Draws the built-in screen of a flow step (email code, two-step
 * verification, enrollment, recovery codes, password reset) with the canvas
 * theme. The customer only designs the sign-in (and sign-up) screen; these
 * follow its colors and font.
 */
@Composable
internal fun StepView(
    flow: FlowController,
    state: FlowState,
    theme: CanvasTheme,
    family: FontFamily?,
    locale: String,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val step = state.step
    val scope = rememberCoroutineScope()
    val textColor = Color(theme.text)
    val muted = Color(theme.muted)
    val primary = Color(theme.primary)
    fun say(key: String, params: Map<String, String> = emptyMap()): String = murkioString(locale, key, params)

    // A new screen starts with empty fields and the focus on the first one.
    var code by remember(step.name) { mutableStateOf("") }
    var password by remember(step.name) { mutableStateOf("") }
    var email by remember(step.name) { mutableStateOf(if (step is FlowStep.Forgot) flow.currentEmail else "") }
    val first = remember(step.name) { FocusRequester() }
    val second = remember(step.name) { FocusRequester() }
    LaunchedEffect(step.name) {
        if (step !is FlowStep.RecoveryCodes) runCatching { first.requestFocus() }
    }

    val fieldLook = ElementLook(
        foreground = textColor,
        fontSize = 16f,
        background = Color(theme.surface),
        borderColor = Color(theme.border),
        borderWidth = 1f,
        radius = theme.radius.toFloat(),
        paddingX = 12f,
        paddingY = 10f,
    )
    val buttonLook = ElementLook(
        foreground = Color(theme.onPrimary),
        fontSize = 16f,
        background = primary,
        radius = theme.radius.toFloat(),
        weight = FontWeight.SemiBold,
        align = TextAlign.Center,
        paddingX = 16f,
    )
    val linkLook = ElementLook(foreground = primary, fontSize = 14f, align = TextAlign.Center)

    @Composable
    fun Heading(title: String, description: String, params: Map<String, String> = emptyMap()) {
        BasicText(
            say(title),
            modifier = Modifier.semantics { heading() },
            style = TextStyle(color = textColor, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = family),
        )
        BasicText(say(description, params), style = TextStyle(color = muted, fontSize = 16.sp, fontFamily = family))
    }

    @Composable
    fun Field(
        value: String,
        onValueChange: (String) -> Unit,
        label: String,
        kind: FieldKind,
        tag: String,
        focus: FocusRequester,
        imeAction: ImeAction = ImeAction.Done,
        onImeAction: () -> Unit,
    ) {
        MurkioTextField(
            value = value,
            onValueChange = onValueChange,
            look = fieldLook,
            family = family,
            label = label,
            hint = label,
            hintColor = muted,
            accent = primary,
            kind = kind,
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
            tag = tag,
            focusRequester = focus,
            imeAction = imeAction,
            onImeAction = onImeAction,
        )
    }

    @Composable
    fun Primary(label: String, onClick: () -> Unit) {
        MurkioButton(
            label, buttonLook, family, onClick,
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("murkio-step-submit"),
            busy = state.busy,
        )
    }

    @Composable
    fun Link(label: String, tag: String, onClick: () -> Unit) {
        MurkioButton(
            label, linkLook, family, onClick,
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag(tag),
            busy = state.busy,
            underline = true,
        )
    }

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 400.dp)
                .fillMaxWidth()
                .background(Color(theme.background))
                .padding(24.dp)
                .testTag("murkio-step-${step.name}"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val controls: @Composable () -> Unit
            when (step) {
                is FlowStep.VerifyEmail -> {
                    Heading("verify.title", "verify.description", mapOf("email" to step.email))
                    val submit = { scope.launch { flow.confirmEmail(code, if (step.needsPassword) password else null) }; Unit }
                    Field(
                        code, { code = it }, say("verify.code"), FieldKind.CODE, "murkio-step-code", first,
                        imeAction = if (step.needsPassword) ImeAction.Next else ImeAction.Done,
                        onImeAction = { if (step.needsPassword) runCatching { second.requestFocus() } else submit() },
                    )
                    if (step.needsPassword) {
                        Field(password, { password = it }, say("verify.password"), FieldKind.PASSWORD, "murkio-step-password", second, onImeAction = submit)
                    }
                    controls = {
                        Primary(say("verify.submit"), submit)
                        Link(say("verify.resend"), "murkio-step-resend") { scope.launch { flow.resendEmail() } }
                    }
                }
                is FlowStep.Mfa -> {
                    Heading("mfa.title", "mfa.description")
                    val submit = { scope.launch { flow.verifyMfa(code) }; Unit }
                    Field(code, { code = it }, say("mfa.code"), FieldKind.CODE, "murkio-step-code", first, onImeAction = submit)
                    controls = { Primary(say("mfa.submit"), submit) }
                }
                is FlowStep.MfaEnroll -> {
                    Heading("enroll.title", "enroll.description")
                    val submit = { scope.launch { flow.confirmEnrollment(code) }; Unit }
                    SelectionContainer {
                        BasicText(
                            buildAnnotatedString {
                                append("${say("enroll.secret")}: ")
                                withStyle(SpanStyle(color = textColor, fontFamily = FontFamily.Monospace, fontSize = 15.sp)) {
                                    append(step.secret)
                                }
                            },
                            modifier = Modifier.testTag("murkio-step-secret"),
                            style = TextStyle(color = muted, fontSize = 16.sp, fontFamily = family),
                        )
                    }
                    if (step.otpauthUri.startsWith("otpauth://")) {
                        Link(say("enroll.open"), "murkio-step-open") { onOpenUrl(step.otpauthUri) }
                    }
                    Field(code, { code = it }, say("enroll.code"), FieldKind.CODE, "murkio-step-code", first, onImeAction = submit)
                    controls = { Primary(say("enroll.submit"), submit) }
                }
                is FlowStep.RecoveryCodes -> {
                    Heading("recovery.title", "recovery.description")
                    SelectionContainer {
                        BasicText(
                            step.codes.withIndex().joinToString("\n") { "${it.index + 1}. ${it.value}" },
                            modifier = Modifier.testTag("murkio-step-codes"),
                            style = TextStyle(color = textColor, fontSize = 15.sp, lineHeight = 24.sp, fontFamily = FontFamily.Monospace),
                        )
                    }
                    controls = { Primary(say("recovery.done")) { scope.launch { flow.finishRecovery() } } }
                }
                is FlowStep.Forgot -> {
                    Heading("forgot.title", "forgot.description")
                    val submit = { scope.launch { flow.requestReset(email) }; Unit }
                    Field(email, { email = it }, say("forgot.email"), FieldKind.EMAIL, "murkio-step-email", first, onImeAction = submit)
                    controls = { Primary(say("forgot.submit"), submit) }
                }
                is FlowStep.Reset -> {
                    Heading("reset.title", "reset.description", mapOf("email" to step.email))
                    val submit = { scope.launch { flow.resetPassword(code, password) }; Unit }
                    Field(
                        code, { code = it }, say("reset.code"), FieldKind.CODE, "murkio-step-code", first,
                        imeAction = ImeAction.Next,
                        onImeAction = { runCatching { second.requestFocus() } },
                    )
                    Field(password, { password = it }, say("reset.password"), FieldKind.NEW_PASSWORD, "murkio-step-password", second, onImeAction = submit)
                    controls = { Primary(say("reset.submit"), submit) }
                }
                FlowStep.SignIn, FlowStep.Done -> controls = {}
            }

            val error = state.error
            val message = when {
                error != null -> translateError(error.code, locale)
                state.notice != null -> say(state.notice)
                else -> null
            }
            if (message != null) {
                BasicText(
                    message,
                    modifier = Modifier.testTag("murkio-step-message").semantics { liveRegion = LiveRegionMode.Polite },
                    style = TextStyle(color = if (error != null) Color(theme.error) else textColor, fontSize = 14.sp, fontFamily = family),
                )
            }

            controls()
            if (step !is FlowStep.RecoveryCodes && state.inStep) {
                Link(say("back"), "murkio-step-back") { flow.back() }
            }
        }
    }
}
