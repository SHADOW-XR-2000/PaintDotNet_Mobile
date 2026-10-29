package app.tilepaint.core

/** Called right before a tile of a layer is created, modified or removed. */
fun interface TileChangeListener {
    /** [currentId] is the tile's id before the change, or null if the tile did not exist. */
    fun beforeTileChange(layer: TileLayer, key: Long, currentId: TileId?)
}

/**
 * A sparse raster layer: a map from tile coordinates to tiles. Tiles that were never painted
 * (or were erased completely) do not exist, so an empty layer costs no pixel memory.
 *
 * Coordinates are image pixels, with (0, 0) in the top left corner.
 */
class TileLayer(
    val width: Int,
    val height: Int,
    private val store: TileStore,
    var name: String = "Layer",
) {
    var visible: Boolean = true
    var opacity: Float = 1f

    /** Normally the document's history, so that every change can be undone. */
    var changeListener: TileChangeListener? = null

    val bounds = IntRect(0, 0, width, height)

    private val tiles = HashMap<Long, TileId>()

    val tileCount: Int get() = tiles.size

    fun getPixel(x: Int, y: Int): Int {
        if (x < 0 || y < 0 || x >= width || y >= height) return 0
        val id = tiles[tileKey(x / TILE_SIZE, y / TILE_SIZE)] ?: return 0
        return store.read(id)[x % TILE_SIZE, y % TILE_SIZE]
    }

    fun setPixel(x: Int, y: Int, argb: Int) {
        if (x < 0 || y < 0 || x >= width || y >= height) return
        val tx = x / TILE_SIZE
        val ty = y / TILE_SIZE
        // Writing transparency where there is no tile changes nothing, so do not allocate one.
        if ((argb ushr 24) == 0 && tileKey(tx, ty) !in tiles) return
        writableTile(tx, ty)[x % TILE_SIZE, y % TILE_SIZE] = argb
    }

    /** Visits existing tiles that intersect [area], read-only. Used for rendering. */
    fun forEachTile(area: IntRect, block: (tx: Int, ty: Int, tile: Tile) -> Unit) {
        val r = area.intersect(bounds)
        if (r.isEmpty()) return
        for (ty in r.top / TILE_SIZE..(r.bottom - 1) / TILE_SIZE) {
            for (tx in r.left / TILE_SIZE..(r.right - 1) / TILE_SIZE) {
                val id = tiles[tileKey(tx, ty)] ?: continue
                block(tx, ty, store.read(id))
            }
        }
    }

    /**
     * Visits the tiles that intersect [area] with write access (copy-on-write and history are
     * handled here). [block] receives the tile, the image coordinates of its top left corner,
     * and the part of [area] inside that tile, in image coordinates.
     *
     * Missing tiles are created unless [createMissing] is false (use that for erasing).
     */
    fun forEachWritableTile(
        area: IntRect,
        createMissing: Boolean = true,
        block: (tile: Tile, originX: Int, originY: Int, clip: IntRect) -> Unit,
    ) {
        val r = area.intersect(bounds)
        if (r.isEmpty()) return
        for (ty in r.top / TILE_SIZE..(r.bottom - 1) / TILE_SIZE) {
            for (tx in r.left / TILE_SIZE..(r.right - 1) / TILE_SIZE) {
                if (!createMissing && tileKey(tx, ty) !in tiles) continue
                val tile = writableTile(tx, ty)
                val originX = tx * TILE_SIZE
                val originY = ty * TILE_SIZE
                val clip = r.intersect(IntRect(originX, originY, originX + TILE_SIZE, originY + TILE_SIZE))
                block(tile, originX, originY, clip)
            }
        }
    }

    private fun writableTile(tx: Int, ty: Int): Tile {
        val key = tileKey(tx, ty)
        var id = tiles[key]
        if (id == null) {
            changeListener?.beforeTileChange(this, key, null)
            id = store.create()
            tiles[key] = id
        } else {
            // The listener (history) takes its own reference first, which makes the tile
            // shared, so writableId() below hands us a private copy and the old one is kept.
            changeListener?.beforeTileChange(this, key, id)
            val writable = store.writableId(id)
            if (writable != id) tiles[key] = writable
            id = writable
        }
        return store.write(id)
    }

    /** Puts [id] (or nothing, if null) at [key] and returns what was there before. For history. */
    internal fun swapTileId(key: Long, id: TileId?): TileId? =
        if (id == null) tiles.remove(key) else tiles.put(key, id)

    /** Frees the tile at [key] if it became fully transparent. For history. */
    internal fun pruneIfTransparent(key: Long) {
        val id = tiles[key] ?: return
        if (store.read(id).isTransparent()) {
            tiles.remove(key)
            store.release(id)
        }
    }
}
