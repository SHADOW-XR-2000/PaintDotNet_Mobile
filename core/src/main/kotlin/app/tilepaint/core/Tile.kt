package app.tilepaint.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer

const val TILE_SIZE = 256
const val TILE_PIXELS = TILE_SIZE * TILE_SIZE
const val TILE_BYTES = TILE_PIXELS * 4

/**
 * A 256x256 block of pixels.
 *
 * The pixels live in a direct (off-heap) buffer, so they do not count against the Java heap limit.
 * Pixel format is straight (non-premultiplied) ARGB packed in an Int, the same as
 * `android.graphics.Color`. A pixel of 0 is fully transparent.
 *
 * Tiles are owned and recycled by [TileStore]; do not keep a reference across store calls.
 */
class Tile {
    internal val bytes: ByteBuffer = ByteBuffer.allocateDirect(TILE_BYTES).order(ByteOrder.nativeOrder())
    private val pixels: IntBuffer = bytes.asIntBuffer()

    operator fun get(x: Int, y: Int): Int = pixels.get(y * TILE_SIZE + x)

    operator fun set(x: Int, y: Int, argb: Int) {
        pixels.put(y * TILE_SIZE + x, argb)
    }

    fun clear() = fill(0)

    fun fill(argb: Int) {
        for (i in 0 until TILE_PIXELS) pixels.put(i, argb)
    }

    fun isTransparent(): Boolean {
        for (i in 0 until TILE_PIXELS) {
            if ((pixels.get(i) ushr 24) != 0) return false
        }
        return true
    }

    fun copyFrom(other: Tile) {
        val src = other.bytes.duplicate()
        src.clear()
        val dst = bytes.duplicate()
        dst.clear()
        dst.put(src)
    }

    /** Copies all pixels into [out] (needs at least [TILE_PIXELS] entries), row by row. */
    fun copyTo(out: IntArray) {
        val view = pixels.duplicate()
        view.clear()
        view.get(out, 0, TILE_PIXELS)
    }

    fun copyFrom(src: IntArray) {
        val view = pixels.duplicate()
        view.clear()
        view.put(src, 0, TILE_PIXELS)
    }
}
