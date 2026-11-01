package it.bitwire.craterush

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import it.bitwire.craterush.game.FrameBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Turns the 1-bit framebuffer into a passive-matrix LCD look:
 * slow pixel response (ghosting), faint pixel grid, integer scaling, bezel.
 */
class LcdRenderer(private val w: Int, private val h: Int) {
    private val bg = 0xFF9DB27E.toInt()
    private val ink = 0xFF1C2614.toInt()
    private val bezel = 0xFF26292E.toInt()

    private val level = FloatArray(w * h)
    private val pixels = IntArray(w * h)
    private val bitmap: Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    private val blitPaint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val gridPaint = Paint().apply { color = 0x2EFFFFFF }
    private val shadowPaint = Paint().apply { color = 0x22000000 }
    private val src = Rect(0, 0, w, h)
    private val dst = Rect()

    /** Last computed LCD rectangle on screen, for touch mapping. */
    val screenRect = Rect()

    fun update(fb: FrameBuffer, dt: Float) {
        val kOn = 1f - exp(-dt / 0.035f)
        val kOff = 1f - exp(-dt / 0.06f)
        for (i in pixels.indices) {
            val t = if (fb.px[i]) 1f else 0f
            val cur = level[i]
            val v = cur + (t - cur) * (if (t > cur) kOn else kOff)
            level[i] = v
            pixels[i] = lerp(bg, ink, v)
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    fun draw(canvas: Canvas) {
        val cw = canvas.width
        val ch = canvas.height
        canvas.drawColor(bezel)
        val margin = (min(cw, ch) * 0.04f).toInt()
        var scale = min((cw - 2 * margin) / w, (ch - 2 * margin) / h)
        scale = max(scale, 1)
        val dw = w * scale
        val dh = h * scale
        val left = (cw - dw) / 2
        val top = (ch - dh) / 2
        dst.set(left, top, left + dw, top + dh)
        screenRect.set(dst)

        // screen glass with a little border of backlight
        val pad = scale * 2
        glassPaint.color = bg
        canvas.drawRect((left - pad).toFloat(), (top - pad).toFloat(),
            (left + dw + pad).toFloat(), (top + dh + pad).toFloat(), glassPaint)

        // drop shadow of pixels on the backlight layer (offset 1/4 pixel)
        val so = max(1, scale / 4)
        canvas.save()
        canvas.translate(so.toFloat(), so.toFloat())
        canvas.drawBitmap(bitmap, src, dst, shadowBlit)
        canvas.restore()
        canvas.drawBitmap(bitmap, src, dst, blitPaint)

        if (scale >= 4) {
            for (x in 0..w) {
                val fx = (left + x * scale).toFloat()
                canvas.drawRect(fx, top.toFloat(), fx + 1, (top + dh).toFloat(), gridPaint)
            }
            for (y in 0..h) {
                val fy = (top + y * scale).toFloat()
                canvas.drawRect(left.toFloat(), fy, (left + dw).toFloat(), fy + 1, gridPaint)
            }
        }
    }

    private val shadowBlit = Paint().apply { isFilterBitmap = false; alpha = 40 }
    private val glassPaint = Paint()

    private fun lerp(a: Int, b: Int, t: Float): Int {
        val ar = (a shr 16) and 0xFF; val ag = (a shr 8) and 0xFF; val ab = a and 0xFF
        val br = (b shr 16) and 0xFF; val bg2 = (b shr 8) and 0xFF; val bb = b and 0xFF
        val r = (ar + (br - ar) * t).toInt()
        val g = (ag + (bg2 - ag) * t).toInt()
        val bl = (ab + (bb - ab) * t).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }
}
