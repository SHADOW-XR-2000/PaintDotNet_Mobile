package app.tilepaint.core

import kotlin.math.max
import kotlin.math.min

/** Integer rectangle. [right] and [bottom] are exclusive. */
data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun isEmpty(): Boolean = right <= left || bottom <= top

    /** The result can have a negative size; check it with [isEmpty]. */
    fun intersect(o: IntRect): IntRect =
        IntRect(max(left, o.left), max(top, o.top), min(right, o.right), min(bottom, o.bottom))
}

/** Packs tile grid coordinates into a single Long, used as a map key. */
fun tileKey(tx: Int, ty: Int): Long = (tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL)

fun tileKeyX(key: Long): Int = (key shr 32).toInt()

fun tileKeyY(key: Long): Int = key.toInt()
