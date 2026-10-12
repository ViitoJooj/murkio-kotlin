package com.murkio.identity.android.canvas

import com.murkio.identity.android.internal.languageOf
import com.murkio.identity.android.internal.normalizeLocale

/** A canvas text: the default and its translations by locale. */
public class LocalizedText(public val fallback: String, public val translations: Map<String, String> = emptyMap()) {
    /** The translation of the exact locale, then of its language, then the default text. */
    public fun pick(locale: String): String {
        val normalized = normalizeLocale(locale)
        return translations[normalized] ?: translations[languageOf(normalized)] ?: fallback
    }
}

/** The kinds of element a canvas can hold. [wire] is the name in the canvas document. */
public enum class CanvasElementType(public val wire: String) {
    EMAIL_INPUT("email_input"),
    PASSWORD_INPUT("password_input"),
    KEEP_LOGGED("keep_logged"),
    LOGIN_BUTTON("login_button"),
    REGISTER_BUTTON("register_button"),
    FORGOT_PASSWORD_LINK("forgot_password_link"),
    OAUTH_BUTTON("oauth_button"),
    ERROR_MESSAGE("error_message"),
    TEXT("text"),
    LINK("link"),
    IMAGE("image"),
    SHAPE("shape"),
    DIVIDER("divider"),
    ;

    /** Decorative elements never take a touch and are painted under the interactive ones. */
    public val decorative: Boolean get() = this == TEXT || this == IMAGE || this == SHAPE || this == DIVIDER

    /** The elements that only make sense with the password login. */
    public val needsPassword: Boolean
        get() = this == EMAIL_INPUT ||
            this == PASSWORD_INPUT ||
            this == KEEP_LOGGED ||
            this == LOGIN_BUTTON ||
            this == REGISTER_BUTTON ||
            this == FORGOT_PASSWORD_LINK

    internal companion object {
        fun parse(value: Any?): CanvasElementType? = entries.firstOrNull { it.wire == value }
    }
}

/** Horizontal alignment of an element's text. */
public enum class CanvasTextAlign { LEFT, CENTER, RIGHT }

/** The shadow presets of a canvas style. */
public enum class CanvasShadow { NONE, SM, MD, LG }

/** The allowed style of an element. Colors are 32-bit ARGB values; a null field means "the default of the element". */
public class CanvasStyle(
    public val color: Int? = null,
    public val background: Int? = null,
    public val borderColor: Int? = null,
    public val borderWidth: Int? = null,
    public val radius: Int? = null,
    public val fontSize: Int? = null,
    public val fontWeight: Int? = null,
    public val textAlign: CanvasTextAlign? = null,
    public val padding: Int? = null,
    public val opacity: Float? = null,
    public val shadow: CanvasShadow? = null,
)

/** One element of a screen: what it is, its texts and its style. */
public class CanvasElement(
    public val id: String,
    public val type: CanvasElementType,
    public val style: CanvasStyle = CanvasStyle(),
    /** Inputs, buttons, the link to "forgot password", the divider. */
    public val label: LocalizedText? = null,
    /** Inputs. */
    public val placeholder: LocalizedText? = null,
    /** `text` and `link`. */
    public val text: LocalizedText? = null,
    /** `image`. */
    public val alt: LocalizedText? = null,
    /** `password_input`: show the button that reveals the password. */
    public val showToggle: Boolean = false,
    /** `keep_logged`: starts checked. */
    public val defaultChecked: Boolean = false,
    /** `oauth_button`. */
    public val provider: String? = null,
    /** `text`: `body` (or empty), `h1`, `h2`, `h3`. */
    public val variant: String = "",
    /** `link`: an https URL or a site path. */
    public val href: String? = null,
    /** `image`: an https URL or a `data:` image. */
    public val src: String? = null,
) {
    public val isHeading: Boolean get() = variant == "h1" || variant == "h2" || variant == "h3"

    internal fun withStyle(style: CanvasStyle): CanvasElement = CanvasElement(
        id, type, style, label, placeholder, text, alt, showToggle, defaultChecked, provider, variant, href, src,
    )
}

/** Where an element sits in a layout. */
public class CanvasItem(public val x: Int, public val y: Int, public val w: Int, public val h: Int, public val z: Int, public val hidden: Boolean = false)

/** The frame of a screen at one size and where each element goes in it. [background] is ARGB, or null for a transparent frame. */
public class CanvasLayout(public val width: Int, public val height: Int, public val items: Map<String, CanvasItem>, public val background: Int? = null)

/** A screen: its elements (the content) and its layouts (the positions). */
public class CanvasScreen(public val elements: List<CanvasElement>, public val desktop: CanvasLayout, public val mobile: CanvasLayout? = null)

/** The font of a canvas. */
public enum class CanvasFont { SYSTEM_SANS, SYSTEM_SERIF, SYSTEM_MONO, INHERIT }

/** The colors (32-bit ARGB), the font and the corner radius of a canvas. */
public class CanvasTheme(
    public val font: CanvasFont,
    public val radius: Int,
    public val primary: Int,
    public val onPrimary: Int,
    public val text: Int,
    public val muted: Int,
    public val background: Int,
    public val surface: Int,
    public val border: Int,
    public val error: Int,
)

/** A validated canvas, ready to draw. Without [signUp] the sign-up uses the sign-in screen. */
public class CanvasDocument(public val theme: CanvasTheme, public val signIn: CanvasScreen, public val signUp: CanvasScreen? = null)
