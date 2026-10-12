package com.murkio.identity.android.canvas

import com.murkio.identity.android.PublicConfig
import com.murkio.identity.android.internal.Json
import com.murkio.identity.android.internal.Spec
import com.murkio.identity.android.internal.list
import com.murkio.identity.android.internal.obj

// The server validates every canvas before it is stored, but the SDK checks
// it again before drawing it in the customer's app (defense in depth: a bug
// or a compromised dashboard must not become a fake link or an invisible
// overlay on every login screen). The rules mirror the server's structural
// ones (libs/spec/canvas); an element that breaks them is dropped, a style
// value out of range is ignored, and a document without what a login needs
// is refused so the caller falls back to the default canvas.

private val PROVIDERS = listOf("google", "github", "apple", "facebook", "x")
private val COLOR_NAMES = listOf("primary", "on_primary", "text", "muted", "background", "surface", "border", "error")
private val FONTS = mapOf(
    "system-sans" to CanvasFont.SYSTEM_SANS,
    "system-serif" to CanvasFont.SYSTEM_SERIF,
    "system-mono" to CanvasFont.SYSTEM_MONO,
    "inherit" to CanvasFont.INHERIT,
)
private val STYLE_KEYS = listOf(
    "color", "background", "border_color", "border_width", "radius", "font_size", "font_weight", "text_align",
    "padding", "opacity", "shadow",
)
private val VARIANTS = listOf("", "body", "h1", "h2", "h3")
private const val MAX_ELEMENTS = 150
private const val MAX_DATA_IMAGES = 4
private const val MAX_DATA_IMAGE_CHARS = 87384 // ceil(64 KiB / 3) * 4

private fun isHex(char: Char): Boolean = char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'

/**
 * Parses a canvas color (`#rgb`, `#rgba`, `#rrggbb`, `#rrggbbaa`, alpha last)
 * into a 32-bit ARGB value. Null when it is not one.
 */
internal fun parseColor(value: Any?): Int? {
    if (value !is String || !value.startsWith("#")) return null
    var hex = value.substring(1)
    if (hex.length !in intArrayOf(3, 4, 6, 8) || !hex.all(::isHex)) return null
    if (hex.length <= 4) hex = hex.map { "$it$it" }.joinToString("")
    if (hex.length == 6) hex += "ff"
    val rgb = hex.substring(0, 6).toInt(16)
    val alpha = hex.substring(6, 8).toInt(16)
    return (alpha shl 24) or rgb
}

private fun isInt(value: Any?, low: Int, high: Int): Boolean =
    value is Double && !value.isNaN() && !value.isInfinite() && value == Math.rint(value) && value >= low && value <= high

private fun int(value: Any?): Int = (value as Double).toInt()

private fun cleanText(value: Any?, maxChars: Int, multiline: Boolean): Boolean {
    if (value !is String || value.isBlank()) return false
    var length = 0
    var index = 0
    while (index < value.length) {
        val code = value.codePointAt(index)
        index += Character.charCount(code)
        if (++length > maxChars) return false
        if (code == 0x0a && multiline) continue
        if (code < 0x20 || (code in 0x7f..0x9f) || (code in 0x202a..0x202e) || (code in 0x2066..0x2069)) return false
    }
    return true
}

private fun isLocale(value: String): Boolean {
    val parts = value.split('-')
    if (parts.size > 2) return false
    val language = parts[0]
    if (language.length !in 2..3 || !language.all { it in 'a'..'z' }) return false
    return parts.size == 1 || (parts[1].length == 2 && parts[1].all { it in 'A'..'Z' })
}

private fun text(value: Any?, maxChars: Int = 120, multiline: Boolean = false): LocalizedText? {
    val map = value.obj() ?: return null
    if (!cleanText(map["default"], maxChars, multiline)) return null
    val translations = LinkedHashMap<String, String>()
    val raw = map["i18n"]
    if (raw != null) {
        val i18n = raw.obj() ?: return null
        if (i18n.size > 20) return null
        for ((locale, translation) in i18n) {
            if (!isLocale(locale) || !cleanText(translation, maxChars, multiline)) return null
            translations[locale] = translation as String
        }
    }
    return LocalizedText(map["default"] as String, translations)
}

private fun hasSpaceOrBackslash(value: String): Boolean = value.any { it == '\\' || it.isWhitespace() }

/** True for an absolute https URL without credentials. */
internal fun isHttpsUrl(value: Any?): Boolean {
    if (value !is String || value.length > 2048 || hasSpaceOrBackslash(value)) return false
    if (!value.startsWith("https://")) return false
    val rest = value.substring("https://".length)
    val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
    val authority = rest.substring(0, end)
    if (authority.isEmpty() || authority.contains('@')) return false
    val host = authority.substringBeforeLast(':', authority).let { if (authority.startsWith("[")) authority else it }
    return host.isNotEmpty() && host != ":"
}

/** True for what a canvas link may point to: an https URL or a site path. */
internal fun isSafeHref(value: Any?): Boolean {
    if (value !is String) return false
    if (value.startsWith("/")) return value.length <= 2048 && !value.startsWith("//") && !hasSpaceOrBackslash(value)
    return isHttpsUrl(value)
}

private val DATA_PREFIXES = listOf("data:image/png;base64,", "data:image/jpeg;base64,", "data:image/gif;base64,")

private fun isDataImage(value: String): Boolean {
    val prefix = DATA_PREFIXES.firstOrNull { value.startsWith(it) } ?: return false
    val data = value.substring(prefix.length).trimEnd('=')
    val padding = value.length - prefix.length - data.length
    return data.isNotEmpty() &&
        padding <= 2 &&
        data.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' }
}

private fun isImageSource(value: Any?): Boolean = isHttpsUrl(value) || (value is String && value.length <= MAX_DATA_IMAGE_CHARS + 32 && isDataImage(value))

/** Builds the element from its props, or null when a prop breaks a rule. Unknown props are simply not read. */
private fun element(id: String, type: CanvasElementType, props: Map<String, Any?>): CanvasElement? {
    fun optional(key: String): LocalizedText? = props[key]?.let { text(it) }
    fun invalidOptional(key: String): Boolean = props[key] != null && text(props[key]) == null

    return when (type) {
        CanvasElementType.EMAIL_INPUT, CanvasElementType.PASSWORD_INPUT -> {
            val label = text(props["label"]) ?: return null
            if (invalidOptional("placeholder")) return null
            CanvasElement(
                id,
                type,
                label = label,
                placeholder = optional("placeholder"),
                showToggle = type == CanvasElementType.PASSWORD_INPUT && props["show_toggle"] == true,
            )
        }
        CanvasElementType.KEEP_LOGGED, CanvasElementType.LOGIN_BUTTON, CanvasElementType.REGISTER_BUTTON,
        CanvasElementType.FORGOT_PASSWORD_LINK,
        -> {
            val label = text(props["label"]) ?: return null
            CanvasElement(
                id,
                type,
                label = label,
                defaultChecked = type == CanvasElementType.KEEP_LOGGED && props["default_checked"] == true,
            )
        }
        CanvasElementType.OAUTH_BUTTON -> {
            val provider = props["provider"] as? String ?: return null
            if (provider !in PROVIDERS || invalidOptional("label")) return null
            CanvasElement(id, type, provider = provider, label = optional("label"))
        }
        CanvasElementType.TEXT -> {
            val content = text(props["text"], maxChars = 500, multiline = true) ?: return null
            val variant = props["variant"]
            if (variant != null && variant !in VARIANTS) return null
            CanvasElement(id, type, text = content, variant = variant as? String ?: "")
        }
        CanvasElementType.LINK -> {
            val content = text(props["text"]) ?: return null
            if (!isSafeHref(props["href"])) return null
            CanvasElement(id, type, text = content, href = props["href"] as String)
        }
        CanvasElementType.IMAGE -> {
            if (invalidOptional("alt") || !isImageSource(props["src"])) return null
            CanvasElement(id, type, alt = optional("alt"), src = props["src"] as String)
        }
        CanvasElementType.DIVIDER -> {
            if (invalidOptional("label")) return null
            CanvasElement(id, type, label = optional("label"))
        }
        CanvasElementType.ERROR_MESSAGE, CanvasElementType.SHAPE -> CanvasElement(id, type)
    }
}

private fun style(value: Any?, problems: MutableList<String>, path: String): CanvasStyle {
    val map = value.obj() ?: return CanvasStyle()
    val color = parseColor(map["color"])
    val background = parseColor(map["background"])
    val borderColor = parseColor(map["border_color"])
    val borderWidth = if (isInt(map["border_width"], 0, 8)) int(map["border_width"]) else null
    val radius = if (isInt(map["radius"], 0, 999)) int(map["radius"]) else null
    val fontSize = if (isInt(map["font_size"], 12, 72)) int(map["font_size"]) else null
    val fontWeight = if (isInt(map["font_weight"], 100, 900) && int(map["font_weight"]) % 100 == 0) int(map["font_weight"]) else null
    val textAlign = when (map["text_align"]) {
        "left" -> CanvasTextAlign.LEFT
        "center" -> CanvasTextAlign.CENTER
        "right" -> CanvasTextAlign.RIGHT
        else -> null
    }
    val padding = if (isInt(map["padding"], 0, 64)) int(map["padding"]) else null
    val rawOpacity = map["opacity"]
    val opacity = if (rawOpacity is Double && rawOpacity >= 0 && rawOpacity <= 1) rawOpacity.toFloat() else null
    val shadow = when (map["shadow"]) {
        "none" -> CanvasShadow.NONE
        "sm" -> CanvasShadow.SM
        "md" -> CanvasShadow.MD
        "lg" -> CanvasShadow.LG
        else -> null
    }

    val read = mapOf(
        "color" to color, "background" to background, "border_color" to borderColor, "border_width" to borderWidth,
        "radius" to radius, "font_size" to fontSize, "font_weight" to fontWeight, "text_align" to textAlign,
        "padding" to padding, "opacity" to opacity, "shadow" to shadow,
    )
    for (key in map.keys) {
        if (key !in STYLE_KEYS || read[key] == null) problems.add("$path.$key: ignored style")
    }
    return CanvasStyle(color, background, borderColor, borderWidth, radius, fontSize, fontWeight, textAlign, padding, opacity, shadow)
}

private class DraftLayout(val width: Int, val height: Int, val background: Int?, val items: MutableMap<String, CanvasItem>) {
    fun build(): CanvasLayout = CanvasLayout(width, height, items.toMap(), background)
}

private fun layout(value: Any?, problems: MutableList<String>, path: String): DraftLayout? {
    val map = value.obj()
    val rawItems = map?.get("items").obj()
    if (map == null || rawItems == null || !isInt(map["width"], 240, 1920) || !isInt(map["height"], 240, 2400)) {
        problems.add("$path: invalid layout")
        return null
    }
    val width = int(map["width"])
    val height = int(map["height"])
    val items = LinkedHashMap<String, CanvasItem>()
    for ((id, raw) in rawItems) {
        val item = raw.obj()
        if (item != null &&
            isInt(item["x"], 0, width) &&
            isInt(item["y"], 0, height) &&
            isInt(item["w"], 1, width) &&
            isInt(item["h"], 1, height) &&
            int(item["x"]) + int(item["w"]) <= width &&
            int(item["y"]) + int(item["h"]) <= height &&
            isInt(item["z"], 0, 1000)
        ) {
            items[id] = CanvasItem(int(item["x"]), int(item["y"]), int(item["w"]), int(item["h"]), int(item["z"]), item["hidden"] == true)
        } else {
            problems.add("$path.items.$id: invalid item")
        }
    }
    return DraftLayout(width, height, parseColor(map["background"].obj()?.get("color")), items)
}

private class Counter(var images: Int = 0)

private fun isElementId(value: String): Boolean = value.length in 1..32 && value.all { it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }

private fun screen(value: Any?, problems: MutableList<String>, path: String, counter: Counter): CanvasScreen? {
    val map = value.obj()
    val rawElements = map?.get("elements").list()
    val layouts = map?.get("layouts").obj()
    if (map == null || rawElements == null || layouts == null) {
        problems.add("$path: invalid screen")
        return null
    }
    val desktop = layout(layouts["desktop"], problems, "$path.layouts.desktop") ?: return null
    val mobile = if (layouts["mobile"] == null) null else layout(layouts["mobile"], problems, "$path.layouts.mobile")
    val present = listOfNotNull(desktop, mobile)

    if (rawElements.size > MAX_ELEMENTS) problems.add("$path.elements: more than $MAX_ELEMENTS elements")

    val ids = HashSet<String>()
    val elements = ArrayList<CanvasElement>()
    for ((index, raw) in rawElements.take(MAX_ELEMENTS).withIndex()) {
        val at = "$path.elements[$index]"
        val entry = raw.obj()
        val id = entry?.get("id") as? String
        if (entry == null || id == null || !isElementId(id) || id in ids) {
            problems.add("$at: invalid or duplicate id")
            continue
        }
        ids.add(id)

        val type = CanvasElementType.parse(entry["type"])
        val rawProps = entry["props"]
        val props = if (rawProps == null) emptyMap() else rawProps.obj()
        val element = if (type == null || props == null) null else element(id, type, props)
        if (element == null) {
            problems.add("$at: invalid ${entry["type"]}")
            continue
        }
        if (element.type == CanvasElementType.IMAGE && element.src!!.startsWith("data:") && ++counter.images > MAX_DATA_IMAGES) {
            problems.add("$at: more than $MAX_DATA_IMAGES embedded images")
            continue
        }
        if (present.any { id !in it.items }) {
            problems.add("$at: missing from a layout")
            continue
        }
        elements.add(element.withStyle(style(entry["style"], problems, "$at.style")))
    }

    for (draft in present) {
        for (id in draft.items.keys.toList()) {
            if (id !in ids) {
                problems.add("$path: item $id has no element")
                draft.items.remove(id)
            }
        }
    }
    return CanvasScreen(elements, desktop.build(), mobile?.build())
}

/**
 * The result of checking a canvas document: the cleaned canvas (null when it
 * cannot be drawn: wrong schema, no sign_in screen or desktop layout, a
 * broken theme) and everything that was wrong, for logging and tests.
 */
public class SanitizeResult(public val document: CanvasDocument?, public val problems: List<String>)

/** Checks a canvas document (JSON text) before it is drawn. */
public fun sanitizeDocument(json: String): SanitizeResult = sanitizeParsed(Json.parseOrNull(json))

internal fun sanitizeParsed(value: Any?): SanitizeResult {
    val problems = ArrayList<String>()
    val root = value.obj()
    val theme = root?.get("theme").obj()
    val screens = root?.get("screens").obj()
    if (root == null || root["schema"] != 1.0 || theme == null || screens == null) {
        return SanitizeResult(null, listOf("not a schema 1 canvas"))
    }

    val rawColors = theme["colors"].obj() ?: emptyMap()
    val colors = COLOR_NAMES.mapNotNull { name -> parseColor(rawColors[name])?.let { name to it } }.toMap()
    val font = FONTS[theme["font"]]
    if (font == null || !isInt(theme["radius"], 0, 32) || colors.size != COLOR_NAMES.size) {
        return SanitizeResult(null, listOf("invalid theme"))
    }

    for (name in screens.keys) {
        if (name != "sign_in" && name != "sign_up") problems.add("screens.$name: unknown screen")
    }

    val counter = Counter()
    val signIn = screen(screens["sign_in"], problems, "screens.sign_in", counter) ?: return SanitizeResult(null, problems)
    val signUp = if (screens["sign_up"] == null) null else screen(screens["sign_up"], problems, "screens.sign_up", counter)

    return SanitizeResult(
        CanvasDocument(
            CanvasTheme(
                font = font,
                radius = int(theme["radius"]),
                primary = colors.getValue("primary"),
                onPrimary = colors.getValue("on_primary"),
                text = colors.getValue("text"),
                muted = colors.getValue("muted"),
                background = colors.getValue("background"),
                surface = colors.getValue("surface"),
                border = colors.getValue("border"),
                error = colors.getValue("error"),
            ),
            signIn,
            signUp,
        ),
        problems,
    )
}

/** True when the screen still has what a password login needs (email, password and a submit action). */
internal fun canSignIn(screen: CanvasScreen): Boolean {
    val types = screen.elements.map { it.type }.toSet()
    return CanvasElementType.EMAIL_INPUT in types &&
        CanvasElementType.PASSWORD_INPUT in types &&
        (CanvasElementType.LOGIN_BUTTON in types || CanvasElementType.REGISTER_BUTTON in types)
}

/** True when the screen can sign a user in with what the application has turned on. */
internal fun isUsable(screen: CanvasScreen, config: PublicConfig?): Boolean {
    if (config != null && !config.passwordEnabled) {
        return screen.elements.any { it.type == CanvasElementType.OAUTH_BUTTON && it.provider in config.oauthProviders }
    }
    return canSignIn(screen)
}

private val DEFAULT: CanvasDocument by lazy { sanitizeDocument(Spec.DEFAULT_CANVAS).document!! }

/** The default canvas (libs/spec/canvas/default.json). */
public fun defaultCanvas(): CanvasDocument = DEFAULT

/**
 * The canvas to draw: the published one when it passes the SDK's own checks
 * and still has what the application's login needs, else the default one.
 * [onProblems] receives what was skipped.
 */
internal fun pickCanvas(raw: Any?, config: PublicConfig?, onProblems: (List<String>) -> Unit = {}): CanvasDocument {
    if (raw == null) return defaultCanvas()
    val result = sanitizeParsed(raw)
    if (result.problems.isNotEmpty()) onProblems(result.problems)
    val document = result.document
    return if (document != null && isUsable(document.signIn, config)) document else defaultCanvas()
}
