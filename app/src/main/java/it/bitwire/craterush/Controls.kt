package it.bitwire.craterush

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.hypot
import kotlin.math.min

/**
 * On-screen buttons in the free space around the LCD: a left/right pad for the left thumb,
 * a big JUMP button for the right thumb and a small pause button.
 * Portrait: under the LCD. Landscape: in strips at both sides of the LCD.
 */
class Controls private constructor(val w: Int, val h: Int, val enabled: Boolean) {

    enum class Btn { LEFT, RIGHT, JUMP, PAUSE }

    /** Area the LCD may use. */
    val lcdArea = Rect(0, 0, w, h)
    val left = RectF()
    val right = RectF()
    val pause = RectF()
    private var jumpX = 0f
    private var jumpY = 0f
    private var jumpR = 0f
    private val pad = RectF()          // generous touch area of the left/right pad

    init {
        if (enabled) {
            val m = min(w, h) * 0.05f
            if (h >= w) {
                val ctrlH = min(h * 0.36f, w * 0.62f)
                val y0 = h - ctrlH
                lcdArea.set(0, 0, w, y0.toInt())
                val s = min(ctrlH * 0.42f, w * 0.2f)
                val cy = y0 + ctrlH * 0.56f
                left.set(m, cy - s / 2, m + s, cy + s / 2)
                right.set(m + s * 1.12f, cy - s / 2, m + s * 2.12f, cy + s / 2)
                jumpR = s * 0.68f; jumpX = w - m - jumpR; jumpY = cy
                pause.set(w / 2f - s * 0.32f, y0 + ctrlH * 0.1f, w / 2f + s * 0.32f, y0 + ctrlH * 0.1f + s * 0.3f)
            } else {
                val sw = min(w * 0.21f, h * 0.5f)
                lcdArea.set(sw.toInt(), 0, (w - sw).toInt(), h)
                val s = sw * 0.42f
                val cy = h * 0.68f
                left.set(sw * 0.06f, cy - s / 2, sw * 0.06f + s, cy + s / 2)
                right.set(sw * 0.94f - s, cy - s / 2, sw * 0.94f, cy + s / 2)
                jumpR = sw * 0.33f; jumpX = w - sw / 2; jumpY = cy
                pause.set(w - sw / 2 - s * 0.35f, h * 0.12f, w - sw / 2 + s * 0.35f, h * 0.12f + s * 0.33f)
            }
            val grow = (left.height()) * 0.35f
            pad.set(left.left - grow, left.top - grow, right.right + grow, left.bottom + grow)
        }
    }

    /** Which button a touch at (x, y) hits, or null. */
    fun hit(x: Float, y: Float): Btn? {
        if (!enabled) return null
        if (pad.contains(x, y)) return if (x < (left.right + right.left) / 2) Btn.LEFT else Btn.RIGHT
        if (hypot(x - jumpX, y - jumpY) < jumpR * 1.35f) return Btn.JUMP
        val g = pause.height() * 0.8f
        if (x in pause.left - g..pause.right + g && y in pause.top - g..pause.bottom + g) return Btn.PAUSE
        return null
    }

    /** Is (x, y) inside the controls region (outside the LCD area)? */
    fun inControlArea(x: Float, y: Float) = enabled && !lcdArea.contains(x.toInt(), y.toInt())

    // ================= drawing =================

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9DB27E.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9DB27E.toInt(); textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val path = Path()

    fun draw(c: Canvas, pressed: Set<Btn>) {
        if (!enabled) return
        edgePaint.strokeWidth = left.height() * 0.04f
        edgePaint.color = 0xFF14161A.toInt()
        val r = left.height() * 0.22f
        key(c, left, r, Btn.LEFT in pressed)
        key(c, right, r, Btn.RIGHT in pressed)
        arrow(c, left, -1)
        arrow(c, right, 1)

        keyPaint.color = if (Btn.JUMP in pressed) KEY_DOWN else KEY_UP
        c.drawCircle(jumpX, jumpY, jumpR, keyPaint)
        c.drawCircle(jumpX, jumpY, jumpR, edgePaint)
        val a = jumpR * 0.32f
        path.reset()
        path.moveTo(jumpX, jumpY - a * 1.3f)
        path.lineTo(jumpX + a, jumpY - a * 0.1f)
        path.lineTo(jumpX - a, jumpY - a * 0.1f)
        path.close()
        c.drawPath(path, glyphPaint)
        textPaint.textSize = jumpR * 0.3f
        c.drawText("JUMP", jumpX, jumpY + jumpR * 0.5f, textPaint)

        key(c, pause, pause.height() / 2, Btn.PAUSE in pressed)
        val bw = pause.height() * 0.14f
        val bh = pause.height() * 0.5f
        val cx = pause.centerX(); val cy = pause.centerY()
        c.drawRect(cx - bw * 2, cy - bh / 2, cx - bw, cy + bh / 2, glyphPaint)
        c.drawRect(cx + bw, cy - bh / 2, cx + bw * 2, cy + bh / 2, glyphPaint)
    }

    private fun key(c: Canvas, rect: RectF, radius: Float, down: Boolean) {
        keyPaint.color = if (down) KEY_DOWN else KEY_UP
        c.drawRoundRect(rect, radius, radius, keyPaint)
        c.drawRoundRect(rect, radius, radius, edgePaint)
    }

    private fun arrow(c: Canvas, rect: RectF, dir: Int) {
        val a = rect.height() * 0.22f
        val cx = rect.centerX(); val cy = rect.centerY()
        path.reset()
        path.moveTo(cx + dir * a, cy)
        path.lineTo(cx - dir * a * 0.7f, cy - a)
        path.lineTo(cx - dir * a * 0.7f, cy + a)
        path.close()
        c.drawPath(path, glyphPaint)
    }

    companion object {
        private const val KEY_UP = 0xFF3A3F47.toInt()
        private const val KEY_DOWN = 0xFF5A6570.toInt()

        @Volatile private var cached: Controls? = null

        /** Layout for a view of size w x h; cached and shared between the UI and game threads. */
        fun layout(w: Int, h: Int, enabled: Boolean): Controls {
            val c = cached
            if (c != null && c.w == w && c.h == h && c.enabled == enabled) return c
            return Controls(w, h, enabled).also { cached = it }
        }
    }
}
