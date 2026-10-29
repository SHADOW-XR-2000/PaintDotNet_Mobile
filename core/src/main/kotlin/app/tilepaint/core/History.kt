package app.tilepaint.core

/**
 * Undo/redo that stores tiles, not whole layers.
 *
 * Before an edit first touches a tile, the history takes a reference to the tile's current
 * contents. [TileStore] copy-on-write then gives the layer a private copy to draw on, and the
 * original stays untouched in the history. A brush stroke over 6 tiles therefore costs 6 tiles,
 * and those spill to disk like any other tile, so memory use does not grow with history depth.
 *
 * Usage: [begin], draw on layers, then [commit] (or [cancel]).
 *
 * [maxRetainedTiles] limits how many old tiles the history keeps. The oldest steps are
 * dropped first. The most recent step is always kept, even if it alone is over the limit.
 */
class History(
    private val store: TileStore,
    private val maxRetainedTiles: Int,
) : TileChangeListener {

    private class Entry(val name: String) {
        /** For each touched tile: the tile as it is NOT currently in the layer (null = no tile). */
        val before = LinkedHashMap<TileLayer, HashMap<Long, TileId?>>()

        /** Number of non-null ids in [before]. Each owns one store reference. */
        var retained = 0
    }

    private val undoStack = ArrayDeque<Entry>()
    private val redoStack = ArrayDeque<Entry>()
    private var open: Entry? = null
    private var retainedTotal = 0

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoDepth: Int get() = undoStack.size
    val redoDepth: Int get() = redoStack.size
    val undoName: String? get() = undoStack.lastOrNull()?.name
    val redoName: String? get() = redoStack.lastOrNull()?.name
    val isRecording: Boolean get() = open != null

    fun begin(name: String) {
        check(open == null) { "an edit is already in progress" }
        open = Entry(name)
    }

    override fun beforeTileChange(layer: TileLayer, key: Long, currentId: TileId?) {
        val entry = open ?: return
        val touched = entry.before.getOrPut(layer) { HashMap() }
        if (touched.containsKey(key)) return
        touched[key] = currentId
        if (currentId != null) {
            store.retain(currentId)
            entry.retained++
            retainedTotal++
        }
    }

    /** Finishes the edit and makes it one undo step. Does nothing if no tile was touched. */
    fun commit() {
        val entry = open ?: return
        open = null
        // Free tiles this edit left fully transparent (eraser). Only touched tiles are checked.
        for ((layer, touched) in entry.before) {
            for (key in touched.keys) layer.pruneIfTransparent(key)
        }
        if (entry.before.isEmpty()) return
        undoStack.addLast(entry)
        dropAll(redoStack)
        enforceBudget()
    }

    /** Abandons the edit and restores every tile it touched. */
    fun cancel() {
        val entry = open ?: return
        open = null
        swap(entry)
        drop(entry)
    }

    fun undo(): Boolean {
        check(open == null) { "cannot undo while an edit is in progress" }
        val entry = undoStack.removeLastOrNull() ?: return false
        swap(entry)
        redoStack.addLast(entry)
        enforceBudget()
        return true
    }

    fun redo(): Boolean {
        check(open == null) { "cannot redo while an edit is in progress" }
        val entry = redoStack.removeLastOrNull() ?: return false
        swap(entry)
        undoStack.addLast(entry)
        enforceBudget()
        return true
    }

    /**
     * Exchanges what the layers hold with what the entry holds. References move along with the
     * ids, so no reference counts change. Applying it twice restores the original state, which
     * is why undo and redo can share it.
     */
    private fun swap(entry: Entry) {
        var retained = 0
        for ((layer, touched) in entry.before) {
            for (item in touched.entries) {
                item.setValue(layer.swapTileId(item.key, item.value))
            }
            retained += touched.values.count { it != null }
        }
        retainedTotal += retained - entry.retained
        entry.retained = retained
    }

    private fun drop(entry: Entry) {
        for (touched in entry.before.values) {
            for (id in touched.values) if (id != null) store.release(id)
        }
        retainedTotal -= entry.retained
        entry.retained = 0
    }

    private fun dropAll(stack: ArrayDeque<Entry>) {
        for (entry in stack) drop(entry)
        stack.clear()
    }

    private fun enforceBudget() {
        while (retainedTotal > maxRetainedTiles && undoStack.size > 1) drop(undoStack.removeFirst())
        while (retainedTotal > maxRetainedTiles && redoStack.size > 1) drop(redoStack.removeFirst())
    }
}
