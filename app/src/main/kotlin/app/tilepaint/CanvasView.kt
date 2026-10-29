package app.tilepaint

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import app.tilepaint.core.Brush
import app.tilepaint.core.BrushMode
import app.tilepaint.core.Document
import app.tilepaint.core.IntRect
import app.tilepaint.core.TILE_PIXELS
import app.tilepaint.core.TILE_SIZE
import app.tilepaint.core.TileLayer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Shows a [Document] and edits it.
 * One finger paints; two fingers pinch to zoom and drag to pan.
 *
 * Rendering is deliberately simple for now: every frame, the visible tiles are copied into a
 * small pool of bitmaps and drawn. When zoomed out, tiles are sampled at a coarser step so the
 * bitmaps stay small. A cached mip pyramid and GL textures will replace this later.
 */
class CanvasView(context: Context, private val doc: Document) : View(context) {

    val brush = Brush(radius = 6f, color = 0xFF000000.toInt())
    var activeLayer: TileLayer = doc.layers.first()

    /** Called after a stroke has been committed to the history. */
    var onEditFinished: (() -> Unit)? = null

    // Image -> screen is: screen = scale * image + offset.
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var fitted = false
    private val toScreen = Matrix()
    private val toImage = Matrix()

    private val tilePaint = Paint()
    private val pagePaint = Paint().apply { color = 0xFFFFFFFF.toInt() }
    private val backdrop = 0xFF2B2B2B.toInt()

    private val full = IntArray(TILE_PIXELS)
    private val small = IntArray(TILE_PIXELS)
    private val bitmaps = ArrayList<Bitmap>()
    private var bitmapSize = 0
    private val dst = RectF()

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // A finger went down but nothing is drawn yet: the stroke starts when the finger moves
    // past the touch slop (or lifts, which makes a dot). This keeps the first finger of a
    // pinch gesture from leaving a dot.
    private var pendingDown = false
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    private var stroking = false
    private var lastX = 0f
    private var lastY = 0f
    private var centroidX = 0f
    private var centroidY = 0f
    private val point = FloatArray(2)

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val target = (scale * detector.scaleFactor).coerceIn(0.02f, 64f)
                val f = target / scale
                // Keep the image point under the fingers fixed while zooming.
                offsetX = detector.focusX - f * (detector.focusX - offsetX)
                offsetY = detector.focusY - f * (detector.focusY - offsetY)
                scale = target
                updateMatrices()
                return true
            }
        },
    )

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (!fitted && w > 0 && h > 0) {
            scale = minOf(w.toFloat() / doc.width, h.toFloat() / doc.height)
            offsetX = (w - doc.width * scale) / 2f
            offsetY = (h - doc.height * scale) / 2f
            fitted = true
            updateMatrices()
        }
    }

    override fun onDetachedFromWindow() {
        bitmaps.clear()
        bitmapSize = 0
        super.onDetachedFromWindow()
    }

    private fun updateMatrices() {
        toScreen.reset()
        toScreen.postScale(scale, scale)
        toScreen.postTranslate(offsetX, offsetY)
        toScreen.invert(toImage)
        invalidate()
    }

    private fun mapToImage(x: Float, y: Float) {
        point[0] = x
        point[1] = y
        toImage.mapPoints(point)
    }

    private fun visibleImageRect(): IntRect {
        val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
        toImage.mapRect(r)
        return IntRect(floor(r.left).toInt(), floor(r.top).toInt(), ceil(r.right).toInt(), ceil(r.bottom).toInt())
    }

    /** Pixels are sampled every [step] pixels, so a tile becomes (256 / step)^2 pixels. */
    private fun sampleStep(): Int {
        var step = 1
        while (step < 16 && scale * step * 2 <= 1f) step *= 2
        return step
    }

    private fun bitmapAt(index: Int, size: Int): Bitmap {
        if (bitmapSize != size) {
            bitmaps.clear()
            bitmapSize = size
        }
        while (bitmaps.size <= index) {
            bitmaps.add(Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888))
        }
        return bitmaps[index]
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(backdrop)

        val page = RectF(0f, 0f, doc.width.toFloat(), doc.height.toFloat())
        toScreen.mapRect(page)
        canvas.drawRect(page, pagePaint)

        canvas.save()
        canvas.concat(toScreen)
        val visible = visibleImageRect()
        val step = sampleStep()
        val size = TILE_SIZE / step
        tilePaint.isFilterBitmap = scale < 1f
        var used = 0
        for (layer in doc.layers) {
            if (!layer.visible) continue
            tilePaint.alpha = (layer.opacity.coerceIn(0f, 1f) * 255f).toInt()
            layer.forEachTile(visible) { tx, ty, tile ->
                val bitmap = bitmapAt(used++, size)
                tile.copyTo(full)
                if (step == 1) {
                    bitmap.setPixels(full, 0, size, 0, 0, size, size)
                } else {
                    var o = 0
                    for (y in 0 until size) {
                        val row = y * step * TILE_SIZE
                        for (x in 0 until size) small[o++] = full[row + x * step]
                    }
                    bitmap.setPixels(small, 0, size, 0, 0, size, size)
                }
                val left = (tx * TILE_SIZE).toFloat()
                val top = (ty * TILE_SIZE).toFloat()
                dst.set(left, top, left + TILE_SIZE, top + TILE_SIZE)
                canvas.drawBitmap(bitmap, null, dst, tilePaint)
            }
        }
        canvas.restore()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pendingDown = true
                downX = e.x
                downY = e.y
                downTime = e.eventTime
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger turns the gesture into zoom/pan. If the stroke has only just
                // begun, it was the first half of a pinch, so throw it away instead of keeping a mark.
                pendingDown = false
                if (stroking) {
                    if (e.eventTime - downTime < PINCH_GRACE_MS) cancelStroke() else endStroke()
                }
                updateCentroid(e, skip = -1)
            }

            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) {
                    val oldX = centroidX
                    val oldY = centroidY
                    updateCentroid(e, skip = -1)
                    offsetX += centroidX - oldX
                    offsetY += centroidY - oldY
                    updateMatrices()
                } else if (pendingDown) {
                    if (hypot(e.x - downX, e.y - downY) > touchSlop) {
                        pendingDown = false
                        beginStroke(downX, downY)
                        extendStroke(e.x, e.y)
                        invalidate()
                    }
                } else if (stroking) {
                    for (i in 0 until e.historySize) extendStroke(e.getHistoricalX(i), e.getHistoricalY(i))
                    extendStroke(e.x, e.y)
                    invalidate()
                }
            }

            MotionEvent.ACTION_POINTER_UP -> updateCentroid(e, skip = e.actionIndex)

            MotionEvent.ACTION_UP -> {
                if (pendingDown) {
                    pendingDown = false
                    beginStroke(downX, downY) // a plain tap leaves a dot
                }
                endStroke()
            }

            MotionEvent.ACTION_CANCEL -> {
                pendingDown = false
                cancelStroke()
            }
        }
        return true
    }

    private fun updateCentroid(e: MotionEvent, skip: Int) {
        var sx = 0f
        var sy = 0f
        var n = 0
        for (i in 0 until e.pointerCount) {
            if (i == skip) continue
            sx += e.getX(i)
            sy += e.getY(i)
            n++
        }
        if (n > 0) {
            centroidX = sx / n
            centroidY = sy / n
        }
    }

    private fun beginStroke(x: Float, y: Float) {
        mapToImage(x, y)
        lastX = point[0]
        lastY = point[1]
        doc.history.begin(if (brush.mode == BrushMode.ERASE) "Erase" else "Brush")
        stroking = true
        brush.stamp(activeLayer, lastX, lastY)
        invalidate()
    }

    private fun extendStroke(x: Float, y: Float) {
        mapToImage(x, y)
        brush.line(activeLayer, lastX, lastY, point[0], point[1])
        lastX = point[0]
        lastY = point[1]
    }

    private fun endStroke() {
        if (!stroking) return
        stroking = false
        doc.history.commit()
        onEditFinished?.invoke()
        invalidate()
    }

    /** Drops the stroke and restores everything it touched; nothing is added to the history. */
    private fun cancelStroke() {
        if (!stroking) return
        stroking = false
        doc.history.cancel()
        invalidate()
    }

    private companion object {
        /** A second finger arriving this soon after the first means the user is pinching, not drawing. */
        const val PINCH_GRACE_MS = 250L
    }
}
