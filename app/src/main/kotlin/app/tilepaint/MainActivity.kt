package app.tilepaint

import android.app.Activity
import android.app.ActivityManager
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import app.tilepaint.core.BrushMode
import app.tilepaint.core.Document
import app.tilepaint.core.TileStore
import java.io.File

class MainActivity : Activity() {

    private lateinit var store: TileStore
    private lateinit var doc: Document
    private lateinit var canvasView: CanvasView
    private lateinit var undoButton: Button
    private lateinit var redoButton: Button
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Tiles beyond the RAM budget are spilled to the app's cache directory.
        val spillDir = File(cacheDir, "tiles").apply {
            deleteRecursively()
            mkdirs()
        }
        val memoryClassMb = (getSystemService(ACTIVITY_SERVICE) as ActivityManager).memoryClass
        store = TileStore(spillDir, memoryClassMb * 1024L * 1024L / 6L)

        doc = Document(4096, 4096, store).also { it.addLayer("Layer 1") }
        canvasView = CanvasView(this, doc)
        canvasView.onEditFinished = { refreshUi() }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(canvasView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(buildToolbar(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setContentView(root)
        refreshUi()
    }

    override fun onDestroy() {
        if (isFinishing) store.close()
        super.onDestroy()
    }

    private fun buildToolbar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1E1E1E.toInt())
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }

        val tools = LinearLayout(this)
        undoButton = button(R.string.undo) {
            doc.history.undo()
            canvasView.invalidate()
            refreshUi()
        }
        redoButton = button(R.string.redo) {
            doc.history.redo()
            canvasView.invalidate()
            refreshUi()
        }
        val brushButton = button(R.string.brush) { canvasView.brush.mode = BrushMode.PAINT }
        val eraserButton = button(R.string.eraser) { canvasView.brush.mode = BrushMode.ERASE }
        for (b in listOf(undoButton, redoButton, brushButton, eraserButton)) {
            tools.addView(b, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        bar.addView(tools)

        val swatches = LinearLayout(this)
        val palette = intArrayOf(
            Color.BLACK, Color.WHITE,
            0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFFFDD835.toInt(),
            0xFF43A047.toInt(), 0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(),
        )
        for (color in palette) {
            val swatch = View(this).apply {
                setBackgroundColor(color)
                setOnClickListener {
                    canvasView.brush.color = color
                    canvasView.brush.mode = BrushMode.PAINT
                }
            }
            val params = LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                setMargins(dp(2), dp(4), dp(2), dp(4))
            }
            swatches.addView(swatch, params)
        }
        bar.addView(swatches)

        // Brush radius in image pixels: 1..100.
        val size = SeekBar(this).apply {
            max = 99
            progress = 5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    canvasView.brush.radius = 1f + progress
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {}

                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
        }
        bar.addView(size, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        status = TextView(this).apply {
            setTextColor(0xFFBBBBBB.toInt())
            textSize = 12f
        }
        bar.addView(status)
        return bar
    }

    private fun button(label: Int, onClick: () -> Unit): Button =
        Button(this).apply {
            setText(label)
            setOnClickListener { onClick() }
        }

    private fun refreshUi() {
        undoButton.isEnabled = doc.history.canUndo
        redoButton.isEnabled = doc.history.canRedo
        status.text = getString(R.string.status, store.residentTiles, store.liveTiles, doc.history.undoDepth)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
