package app.tilepaint.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

enum class BrushMode { PAINT, ERASE }

/** Pixel blending on straight (non-premultiplied) ARGB ints. */
object Blend {
    /** Standard "source over" compositing of [src] on top of [dst]. */
    fun over(dst: Int, src: Int): Int {
        val sa = src ushr 24
        if (sa == 255) return src
        if (sa == 0) return dst
        val da = dst ushr 24
        if (da == 0) return src
        val inv = 255 - sa
        val den = sa * 255 + da * inv
        val a = (den + 127) / 255
        val r = channel(src shr 16, dst shr 16, sa, da, inv, den)
        val g = channel(src shr 8, dst shr 8, sa, da, inv, den)
        val b = channel(src, dst, sa, da, inv, den)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Makes [dst] more transparent by [coverage] (0..1). Colour channels are kept. */
    fun erase(dst: Int, coverage: Float): Int {
        val a = ((dst ushr 24) * (1f - coverage) + 0.5f).toInt()
        return (a shl 24) or (dst and 0x00FFFFFF)
    }

    private fun channel(s: Int, d: Int, sa: Int, da: Int, inv: Int, den: Int): Int =
        ((s and 0xFF) * sa * 255 + (d and 0xFF) * da * inv + den / 2) / den
}

/**
 * A round, anti-aliased brush that stamps discs along a path.
 * [radius] is in image pixels. [color] is straight ARGB.
 *
 * Overlapping stamps of a semi-transparent colour add up; a per-stroke buffer will fix that later.
 */
class Brush(
    var radius: Float = 4f,
    var color: Int = 0xFF000000.toInt(),
    var mode: BrushMode = BrushMode.PAINT,
) {
    fun stamp(layer: TileLayer, cx: Float, cy: Float) {
        val r = radius
        val paint = mode == BrushMode.PAINT
        // Pixels whose centre is closer than r + 0.5 to (cx, cy) get some coverage.
        val area = IntRect(floor(cx - r).toInt(), floor(cy - r).toInt(), ceil(cx + r).toInt(), ceil(cy + r).toInt())
        layer.forEachWritableTile(area, createMissing = paint) { tile, originX, originY, clip ->
            for (y in clip.top until clip.bottom) {
                val dy = y + 0.5f - cy
                for (x in clip.left until clip.right) {
                    val dx = x + 0.5f - cx
                    val coverage = (r + 0.5f - sqrt(dx * dx + dy * dy)).coerceIn(0f, 1f)
                    if (coverage <= 0f) continue
                    val current = tile[x - originX, y - originY]
                    tile[x - originX, y - originY] =
                        if (paint) Blend.over(current, scaleAlpha(color, coverage)) else Blend.erase(current, coverage)
                }
            }
        }
    }

    /** Stamps along the segment, excluding the start point (it was stamped by the previous call). */
    fun line(layer: TileLayer, x0: Float, y0: Float, x1: Float, y1: Float) {
        val distance = hypot(x1 - x0, y1 - y0)
        if (distance < 0.01f) return
        val spacing = max(0.5f, radius / 3f)
        val steps = max(1, ceil(distance / spacing).toInt())
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            stamp(layer, x0 + (x1 - x0) * t, y0 + (y1 - y0) * t)
        }
    }

    private fun scaleAlpha(argb: Int, factor: Float): Int {
        val a = ((argb ushr 24) * factor + 0.5f).toInt()
        return (a shl 24) or (argb and 0x00FFFFFF)
    }
}
