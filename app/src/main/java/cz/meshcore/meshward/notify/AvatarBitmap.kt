package cz.meshcore.meshward.notify

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import cz.meshcore.meshward.AvatarStyle
import java.security.MessageDigest

/**
 * Renders the same avatars the in-app [cz.meshcore.meshward.ui.Avatar] composable draws, but to a
 * plain [Bitmap] for use as a notification's [androidx.core.app.Person] / large icon. Notifications
 * run outside Compose, so the identicon/initials logic is duplicated here (kept in sync by eye with
 * Components.kt — same palettes, same MD5 5×3-mirrored-grid identicon).
 *
 * Results are cached by key so a busy conversation doesn't re-hash/re-draw on every message.
 */
object AvatarBitmap {
    // Mirrors Components.kt `identiconColors` (vivid spread across the hue wheel).
    private val identiconColors = intArrayOf(
        0xFFE53935.toInt(), 0xFFD81B60.toInt(), 0xFF8E24AA.toInt(), 0xFF5E35B1.toInt(),
        0xFF3949AB.toInt(), 0xFF1E88E5.toInt(), 0xFF00ACC1.toInt(), 0xFF00897B.toInt(),
        0xFF43A047.toInt(), 0xFF7CB342.toInt(), 0xFFC0CA33.toInt(), 0xFFFDD835.toInt(),
        0xFFFFB300.toInt(), 0xFFFB8C00.toInt(), 0xFFF4511E.toInt(), 0xFF6D4C41.toInt(),
    )

    // Mirrors Components.kt `avatarColors` (initials-circle backgrounds).
    private val avatarColors = intArrayOf(
        0xFF6750A4.toInt(), 0xFF1565C0.toInt(), 0xFF2E7D32.toInt(), 0xFFB71C1C.toInt(),
        0xFFEF6C00.toInt(), 0xFF00838F.toInt(), 0xFFAD1457.toInt(), 0xFF4527A0.toInt(),
    )

    private const val SIZE = 128 // px; plenty for the notification person/large icon
    private val cache = object : LinkedHashMap<String, Bitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Bitmap>) = size > 64
    }

    /**
     * An avatar bitmap for one conversation party. [identiconKey] is the 32-byte public key (hex);
     * when [style] is IDENTICON and a key is present, draws the identicon, otherwise an initials
     * circle seeded by [seed] (the peer id) and labelled from [label] (the display name). [dark]
     * picks the identicon backdrop to match the notification shade (the system theme), mirroring the
     * in-app identicon which swaps only its backdrop between themes.
     */
    @Synchronized
    fun get(style: AvatarStyle, seed: String, label: String, identiconKey: String?, dark: Boolean): Bitmap {
        val useIdenticon = style == AvatarStyle.IDENTICON && !identiconKey.isNullOrBlank()
        // Backdrop depends on theme, so the identicon cache key must too; initials are theme-independent.
        val cacheKey = if (useIdenticon) "i:${if (dark) "d" else "l"}:$identiconKey" else "n:$seed:${label.take(2)}"
        cache[cacheKey]?.let { return it }
        val bmp = if (useIdenticon) drawIdenticon(identiconKey!!, dark) else drawInitials(seed, label)
        cache[cacheKey] = bmp
        return bmp
    }

    /** Whether the system is in night mode — the notification shade follows this, not the app theme. */
    fun isSystemDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun drawIdenticon(key: String, dark: Boolean): Bitmap {
        val digest = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        val fg = identiconColors[((digest[5].toInt() and 0xFF) xor (digest[11].toInt() and 0xFF)) % identiconColors.size]
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        // Matches Components.kt: dark backdrop in dark mode, light otherwise; foreground stays vivid.
        canvas.drawColor(if (dark) 0xFF26262A.toInt() else 0xFFEDEDED.toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fg }
        val cell = SIZE / 5f
        for (row in 0 until 5) {
            for (col in 0 until 3) {
                val on = (digest[row * 3 + col].toInt() and 1) == 0
                if (!on) continue
                for (c in intArrayOf(col, 4 - col)) {
                    canvas.drawRect(c * cell, row * cell, c * cell + cell, row * cell + cell, paint)
                }
            }
        }
        return bmp
    }

    private fun drawInitials(seed: String, label: String): Bitmap {
        val color = avatarColors[(seed.hashCode() ushr 1) % avatarColors.size]
        val initials = label.trim().take(2).uppercase().ifBlank { "?" }
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        canvas.drawCircle(SIZE / 2f, SIZE / 2f, SIZE / 2f, bg)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textSize = SIZE / 2.4f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val bounds = Rect()
        text.getTextBounds(initials, 0, initials.length, bounds)
        canvas.drawText(initials, SIZE / 2f, SIZE / 2f - bounds.exactCenterY(), text)
        return bmp
    }
}
