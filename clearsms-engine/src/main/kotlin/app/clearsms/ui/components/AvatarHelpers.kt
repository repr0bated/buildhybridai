package app.clearsms.ui.components

import androidx.core.graphics.ColorUtils
import kotlin.math.roundToInt

/**
 * Avatar helpers used by the notification icon factory. Lifted from Clear SMS
 * SenderAvatar / SenderBrandMark so the engine can compile without those
 * Compose screens.
 */
private val AVATAR_HUES = listOf(10f, 45f, 90f, 160f, 200f, 230f, 265f, 300f, 330f)
private const val PLAIN_AVATAR_SATURATION = 0.45f
private const val PLAIN_AVATAR_LIGHTNESS = 0.62f
private const val PLAIN_AVATAR_TINT_ALPHA = 0.35f

fun plainAvatarInitial(name: String): String =
    name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "#"

fun plainAvatarHue(name: String): Float = AVATAR_HUES[avatarHueIndex(name)]

fun plainAvatarColorArgb(name: String, surfaceArgb: Int): Int {
    val tone = ColorUtils.HSLToColor(floatArrayOf(plainAvatarHue(name), PLAIN_AVATAR_SATURATION, PLAIN_AVATAR_LIGHTNESS))
    val tint = ColorUtils.setAlphaComponent(tone, (PLAIN_AVATAR_TINT_ALPHA * 255).roundToInt())
    return ColorUtils.compositeColors(tint, surfaceArgb)
}

internal fun avatarHueIndex(name: String): Int = Math.floorMod(name.hashCode(), AVATAR_HUES.size)

internal fun initialsOf(name: String): String {
    val letters = name.split(' ', '-', '_').mapNotNull { word -> word.firstOrNull { it.isLetterOrDigit() } }.take(2)
    return if (letters.isEmpty()) "#" else letters.joinToString("").uppercase()
}
