package app.tilepaint.core

/**
 * An image made of layers (bottom to top) that share one [TileStore] and one [History].
 *
 * Adding, removing and reordering layers is not undoable yet.
 */
class Document(
    val width: Int,
    val height: Int,
    val store: TileStore,
    maxHistoryTiles: Int = 400,
) : AutoCloseable {

    val history = History(store, maxHistoryTiles)

    private val layerList = ArrayList<TileLayer>()
    val layers: List<TileLayer> get() = layerList

    fun addLayer(name: String = "Layer ${layerList.size + 1}"): TileLayer {
        val layer = TileLayer(width, height, store, name)
        layer.changeListener = history
        layerList.add(layer)
        return layer
    }

    /** Runs [block] as a single undoable step. If it throws, everything it changed is rolled back. */
    fun edit(name: String, block: () -> Unit) {
        history.begin(name)
        try {
            block()
        } catch (t: Throwable) {
            history.cancel()
            throw t
        }
        history.commit()
    }

    override fun close() = store.close()
}
