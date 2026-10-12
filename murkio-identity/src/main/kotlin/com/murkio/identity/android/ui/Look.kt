package com.murkio.identity.android.ui

import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.murkio.identity.android.canvas.CanvasElement
import com.murkio.identity.android.canvas.CanvasElementType
import com.murkio.identity.android.canvas.CanvasFont
import com.murkio.identity.android.canvas.CanvasShadow
import com.murkio.identity.android.canvas.CanvasTextAlign
import com.murkio.identity.android.canvas.CanvasTheme

/** How an element is painted: its defaults with the canvas style on top. */
internal class ElementLook(
    val foreground: Color,
    val fontSize: Float,
    val background: Color? = null,
    val borderColor: Color? = null,
    val borderWidth: Float = 0f,
    val radius: Float = 0f,
    val weight: FontWeight = FontWeight.Normal,
    val align: TextAlign = TextAlign.Start,
    val paddingX: Float = 0f,
    val paddingY: Float = 0f,
    val opacity: Float = 1f,
    val elevation: Float = 0f,
) {
    val shape: Shape get() = RoundedCornerShape(radius.dp)

    /** Where a single line of text sits in its box. */
    fun alignment(top: Boolean): Alignment = when (align) {
        TextAlign.Center -> if (top) Alignment.TopCenter else Alignment.Center
        TextAlign.Right, TextAlign.End -> if (top) Alignment.TopEnd else Alignment.CenterEnd
        else -> if (top) Alignment.TopStart else Alignment.CenterStart
    }

    fun textStyle(family: FontFamily?, underline: Boolean = false): TextStyle = TextStyle(
        color = foreground,
        fontSize = fontSize.sp,
        fontWeight = weight,
        fontFamily = family,
        textAlign = align,
        textDecoration = if (underline) TextDecoration.Underline else null,
    )
}

// The CSS shadows of the web canvas, as the elevation that looks closest.
private fun elevationOf(shadow: CanvasShadow?): Float = when (shadow) {
    CanvasShadow.SM -> 1f
    CanvasShadow.MD -> 4f
    CanvasShadow.LG -> 12f
    else -> 0f
}

/** The look of [element]: the defaults of its type (from the canvas theme) with the element's own style on top. */
internal fun resolveLook(element: CanvasElement, theme: CanvasTheme): ElementLook {
    val style = element.style
    var foreground = Color(theme.text)
    var background: Color? = null
    var border: Color? = null
    var borderWidth = 0f
    var radius = 0f
    var fontSize = 16f
    var weight = FontWeight.Normal
    var align = TextAlign.Start
    var paddingX = 0f
    var paddingY = 0f

    when (element.type) {
        CanvasElementType.EMAIL_INPUT, CanvasElementType.PASSWORD_INPUT -> {
            background = Color(theme.surface)
            border = Color(theme.border)
            borderWidth = 1f
            radius = theme.radius.toFloat()
            paddingX = 12f
        }
        CanvasElementType.LOGIN_BUTTON, CanvasElementType.REGISTER_BUTTON -> {
            foreground = Color(theme.onPrimary)
            background = Color(theme.primary)
            radius = theme.radius.toFloat()
            weight = FontWeight.SemiBold
            align = TextAlign.Center
            paddingX = 16f
        }
        CanvasElementType.OAUTH_BUTTON -> {
            background = Color(theme.surface)
            border = Color(theme.border)
            borderWidth = 1f
            radius = theme.radius.toFloat()
            weight = FontWeight.SemiBold
            align = TextAlign.Center
            paddingX = 16f
        }
        CanvasElementType.FORGOT_PASSWORD_LINK, CanvasElementType.LINK -> {
            foreground = Color(theme.primary)
            fontSize = 14f
        }
        CanvasElementType.KEEP_LOGGED -> fontSize = 14f
        CanvasElementType.ERROR_MESSAGE -> {
            foreground = Color(theme.error)
            fontSize = 14f
        }
        CanvasElementType.DIVIDER -> {
            foreground = Color(theme.muted)
            fontSize = 14f
        }
        CanvasElementType.TEXT -> if (element.isHeading) weight = FontWeight.Bold
        CanvasElementType.IMAGE, CanvasElementType.SHAPE -> Unit
    }

    style.color?.let { foreground = Color(it) }
    style.background?.let { background = Color(it) }
    if (style.borderColor != null || style.borderWidth != null) {
        // Only a color means a 1px border; only a width takes the color the
        // element already has (its default border, else its text color).
        borderWidth = (style.borderWidth ?: 1).toFloat()
        border = style.borderColor?.let { Color(it) } ?: border ?: foreground
    }
    style.radius?.let { radius = it.toFloat() }
    style.fontSize?.let { fontSize = it.toFloat() }
    style.fontWeight?.let { weight = FontWeight(it) }
    style.textAlign?.let {
        align = when (it) {
            CanvasTextAlign.LEFT -> TextAlign.Left
            CanvasTextAlign.CENTER -> TextAlign.Center
            CanvasTextAlign.RIGHT -> TextAlign.Right
        }
    }
    style.padding?.let {
        paddingX = it.toFloat()
        paddingY = it.toFloat()
    }

    return ElementLook(
        foreground = foreground,
        fontSize = fontSize,
        background = background,
        borderColor = border,
        borderWidth = borderWidth,
        radius = radius,
        weight = weight,
        align = align,
        paddingX = paddingX,
        paddingY = paddingY,
        opacity = style.opacity ?: 1f,
        elevation = elevationOf(style.shadow),
    )
}

/**
 * The font of a canvas theme. `inherit` keeps the font the app passes to the
 * canvas ([inherited]), which is how an app shares its own typeface.
 */
internal fun themeFontFamily(theme: CanvasTheme, inherited: FontFamily?): FontFamily? = when (theme.font) {
    CanvasFont.INHERIT -> inherited
    CanvasFont.SYSTEM_SERIF -> FontFamily.Serif
    CanvasFont.SYSTEM_MONO -> FontFamily.Monospace
    CanvasFont.SYSTEM_SANS -> FontFamily.Default
}

/** Paints the box of an element: opacity, shadow, background, border, then the padding around its content. */
internal fun Modifier.elementBox(look: ElementLook): Modifier {
    var result = this
    if (look.opacity < 1f) result = result.alpha(look.opacity)
    if (look.elevation > 0f) result = result.shadow(look.elevation.dp, look.shape, clip = false)
    look.background?.let { result = result.background(it, look.shape) }
    val hasBorder = look.borderWidth > 0f && look.borderColor != null
    if (hasBorder) result = result.border(look.borderWidth.dp, look.borderColor!!, look.shape)
    // The border is drawn inside the box, so the content starts after it, like a CSS border-box.
    val inset = if (hasBorder) look.borderWidth else 0f
    return result.padding(horizontal = (look.paddingX + inset).dp, vertical = (look.paddingY + inset).dp)
}

/** The language of the device. `Configuration.getLocales` only exists from Android 7.0 (API 24). */
internal fun Configuration.primaryLanguageTag(): String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
    locales.get(0)?.toLanguageTag()
} else {
    @Suppress("DEPRECATION")
    locale?.toLanguageTag()
}
