package app.tilepaint.core

import java.io.File
import java.nio.file.Files

/** A document with a small memory budget and a temporary spill directory. */
internal class TestEnv(
    budgetTiles: Int = 64,
    historyTiles: Int = 64,
    width: Int = 1000,
    height: Int = 1000,
) : AutoCloseable {
    val dir: File = Files.createTempDirectory("tilepaint-test").toFile()
    val store = TileStore(dir, budgetTiles.toLong() * TILE_BYTES)
    val doc = Document(width, height, store, historyTiles)
    val layer: TileLayer = doc.addLayer("L")

    override fun close() {
        store.close()
        dir.deleteRecursively()
    }
}

internal const val RED = 0xFFFF0000.toInt()
internal const val BLUE = 0xFF0000FF.toInt()
