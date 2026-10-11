package com.murkio.identity.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/** What a text field of the SDK holds, which decides its keyboard and what a password manager is told. */
internal enum class FieldKind { EMAIL, PASSWORD, NEW_PASSWORD, CODE }

/**
 * A text field painted by the SDK: the box comes from [look], and the field
 * inside it is the plain foundation one, so the canvas looks the same in
 * every app, with or without Material.
 */
@Composable
internal fun MurkioTextField(
    value: String,
    onValueChange: (String) -> Unit,
    look: ElementLook,
    family: FontFamily?,
    label: String,
    hint: String,
    hintColor: Color,
    accent: Color,
    kind: FieldKind,
    modifier: Modifier = Modifier,
    tag: String? = null,
    focusRequester: FocusRequester? = null,
    obscure: Boolean = kind == FieldKind.PASSWORD || kind == FieldKind.NEW_PASSWORD,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
    trailing: (@Composable () -> Unit)? = null,
) {
    val style = look.textStyle(family)
    val type = when (kind) {
        FieldKind.EMAIL -> ContentType.Username + ContentType.EmailAddress
        FieldKind.PASSWORD -> ContentType.Password
        FieldKind.NEW_PASSWORD -> ContentType.NewPassword
        FieldKind.CODE -> ContentType.SmsOtpCode
    }
    // The name, the autofill type and the test tag sit on the field itself, so
    // a screen reader and a password manager find them on the node that edits.
    var field = modifier.semantics {
        contentDescription = label
        contentType = type
    }
    if (tag != null) field = field.testTag(tag)
    if (focusRequester != null) field = field.focusRequester(focusRequester)

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = field,
        singleLine = true,
        textStyle = style,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = when (kind) {
                FieldKind.EMAIL -> KeyboardType.Email
                FieldKind.CODE -> KeyboardType.Ascii
                else -> KeyboardType.Password
            },
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(onAny = { onImeAction() }),
        visualTransformation = if (obscure) PasswordVisualTransformation() else VisualTransformation.None,
        cursorBrush = SolidColor(accent),
        decorationBox = { inner ->
            Row(Modifier.fillMaxWidth().elementBox(look), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        BasicText(hint, style = style.copy(color = hintColor, fontWeight = FontWeight.Normal), maxLines = 1)
                    }
                    inner()
                }
                trailing?.invoke()
            }
        },
    )
}

/**
 * A button painted by the SDK. [busy] disables and dims it while a request
 * runs. With [underline] it is drawn as a link; [top] aligns its text to the
 * top of the box instead of the middle.
 */
@Composable
internal fun MurkioButton(
    label: String,
    look: ElementLook,
    family: FontFamily?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    underline: Boolean = false,
    top: Boolean = false,
) {
    Box(
        modifier
            .alpha(if (busy) 0.6f else 1f)
            .clip(look.shape)
            .clickable(enabled = !busy, role = Role.Button, onClick = onClick)
            .elementBox(look),
        contentAlignment = look.alignment(top),
    ) {
        BasicText(label, style = look.textStyle(family, underline))
    }
}

/** The box of the "keep me signed in" checkbox, drawn so the SDK needs no icon set. */
@Composable
internal fun CheckBox(checked: Boolean, accent: Color, onAccent: Color, outline: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(18.dp)) {
        val radius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
        if (checked) {
            drawRoundRect(accent, cornerRadius = radius)
            val check = Path().apply {
                moveTo(size.width * 0.22f, size.height * 0.52f)
                lineTo(size.width * 0.42f, size.height * 0.72f)
                lineTo(size.width * 0.78f, size.height * 0.30f)
            }
            drawPath(check, onAccent, style = Stroke(size.width * 0.13f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        } else {
            val stroke = 1.5.dp.toPx()
            drawRoundRect(
                outline,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                cornerRadius = radius,
                style = Stroke(stroke),
            )
        }
    }
}

/** The eye of the "show password" button; [crossed] draws the "hide" state. */
@Composable
internal fun Eye(color: Color, crossed: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(w * 0.08f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val outline = Path().apply {
            moveTo(w * 0.08f, h * 0.5f)
            quadraticTo(w * 0.5f, h * 0.02f, w * 0.92f, h * 0.5f)
            quadraticTo(w * 0.5f, h * 0.98f, w * 0.08f, h * 0.5f)
            close()
        }
        drawPath(outline, color, style = stroke)
        drawCircle(color, radius = w * 0.14f, style = stroke)
        if (crossed) {
            drawLine(color, Offset(w * 0.18f, h * 0.86f), Offset(w * 0.82f, h * 0.14f), strokeWidth = w * 0.08f, cap = StrokeCap.Round)
        }
    }
}

/** Fills the box of an element. */
internal val Fill: Modifier = Modifier.fillMaxSize()
