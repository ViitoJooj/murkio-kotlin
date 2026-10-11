package com.murkio.identity.android.canvas

import com.murkio.identity.android.PublicConfig

/** The layout a canvas is drawn with. [wire] is the name in the shared vectors. */
public enum class CanvasLayoutName(public val wire: String) { DESKTOP("desktop"), MOBILE("mobile"), STACK("stack") }

/**
 * Layouts only shrink, and never below this: under it the text would not fit
 * the boxes, so the mobile layout (or the stack) takes over.
 */
internal const val MIN_SCALE = 0.75f

internal const val STACK_PADDING_X = 16f
internal const val STACK_PADDING_Y = 24f
internal const val STACK_GAP = 12f

/** Picks the layout for the width the canvas really has (its container, not the screen). */
internal fun chooseLayout(screen: CanvasScreen, width: Float): CanvasLayoutName {
    if (width >= screen.desktop.width * MIN_SCALE) return CanvasLayoutName.DESKTOP
    val mobile = screen.mobile
    if (mobile != null && width >= mobile.width * MIN_SCALE) return CanvasLayoutName.MOBILE
    return CanvasLayoutName.STACK
}

/**
 * The elements top to bottom, then left to right, by the desktop layout: the
 * order of focus, of the screen reader and of the stacked layout.
 */
internal fun readingOrder(screen: CanvasScreen): List<CanvasElement> {
    val items = screen.desktop.items
    return screen.elements.withIndex().sortedWith(
        compareBy<IndexedValue<CanvasElement>>({ items.getValue(it.value.id).y }, { items.getValue(it.value.id).x }, { it.index }),
    ).map { it.value }
}

/**
 * Whether the application's config shows the element. A null [config] shows
 * everything (preview). [oauth] is false when the app has no return link for
 * the social sign-in: a button that cannot come back is hidden.
 */
internal fun isVisible(element: CanvasElement, config: PublicConfig?, oauth: Boolean): Boolean {
    if (element.type == CanvasElementType.OAUTH_BUTTON && !oauth) return false
    if (config == null) return true
    if (!config.passwordEnabled && element.type.needsPassword) return false
    if (element.type == CanvasElementType.OAUTH_BUTTON) return element.provider in config.oauthProviders
    return true
}

/** What "submit" does (the keyboard's action button): `login` on the sign-in screen with a visible login button, else `register`. */
internal fun submitAction(screen: CanvasScreen, signUp: Boolean, config: PublicConfig?, oauth: Boolean): String {
    val hasLogin = screen.elements.any { it.type == CanvasElementType.LOGIN_BUTTON && isVisible(it, config, oauth) }
    return if (hasLogin && !signUp) "login" else "register"
}

/** A box in the frame, in canvas pixels (dp) from its top left corner. */
public class CanvasRect(public val x: Float, public val y: Float, public val w: Float, public val h: Float)

/** Where everything goes for one container width. */
public class CanvasPlacement internal constructor(
    public val layout: CanvasLayoutName,
    public val frameWidth: Float,
    public val frameHeight: Float,
    /** 32-bit ARGB, or null for a transparent frame. */
    public val background: Int?,
    /** The drawn elements in reading order. */
    public val order: List<CanvasElement>,
    /** The drawn elements bottom to top. */
    public val paint: List<CanvasElement>,
    public val rects: Map<String, CanvasRect>,
)

/** Lays a screen out for a container [width]. */
internal fun place(screen: CanvasScreen, width: Float, config: PublicConfig?, oauth: Boolean): CanvasPlacement {
    val name = chooseLayout(screen, width)
    val layout = if (name == CanvasLayoutName.MOBILE) screen.mobile!! else screen.desktop
    val frameWidth = minOf(width, layout.width.toFloat())
    val shown = readingOrder(screen).filter { isVisible(it, config, oauth) && !layout.items.getValue(it.id).hidden }
    val rects = LinkedHashMap<String, CanvasRect>()

    if (name == CanvasLayoutName.STACK) {
        var y = STACK_PADDING_Y
        for (element in shown) {
            val height = layout.items.getValue(element.id).h.toFloat()
            rects[element.id] = CanvasRect(STACK_PADDING_X, y, frameWidth - 2 * STACK_PADDING_X, height)
            y += height + STACK_GAP
        }
        val frameHeight = if (shown.isEmpty()) 2 * STACK_PADDING_Y else y - STACK_GAP + STACK_PADDING_Y
        return CanvasPlacement(name, frameWidth, frameHeight, layout.background, shown, shown, rects)
    }

    val scale = frameWidth / layout.width
    for (element in shown) {
        val item = layout.items.getValue(element.id)
        rects[element.id] = CanvasRect(item.x * scale, item.y * scale, item.w * scale, item.h * scale)
    }
    fun depth(element: CanvasElement): Int = (if (element.type.decorative) 0 else 2000) + layout.items.getValue(element.id).z
    val paint = shown.withIndex().sortedWith(compareBy({ depth(it.value) }, { it.index })).map { it.value }
    return CanvasPlacement(name, frameWidth, layout.height * scale, layout.background, shown, paint, rects)
}
