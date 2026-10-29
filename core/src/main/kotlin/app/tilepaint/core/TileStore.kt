package app.tilepaint.core

import java.io.File
import java.io.RandomAccessFile

typealias TileId = Long

/**
 * Owns every tile of a document and keeps RAM use under a fixed budget.
 *
 * - Tiles are addressed by [TileId] and reference counted. A layer holds one reference to each
 *   of its tiles, and an undo step holds one reference to each tile it can restore.
 * - Only the most recently used tiles stay in memory (LRU). The rest are written to [spillDir]
 *   and read back on demand, so memory use does not grow with image size or history length.
 * - Copy-on-write: a tile with more than one reference is never modified in place; ask
 *   [writableId] for a private copy first.
 *
 * Not thread safe. A [Tile] returned by [read] or [write] is only valid until the next call
 * on this store, because it may be evicted and its memory reused.
 */
class TileStore(
    private val spillDir: File,
    memoryBudgetBytes: Long,
) : AutoCloseable {

    private class Slot(var refs: Int) {
        /** A valid copy of the pixels exists in the spill directory. */
        var onDisk = false

        /** The copy in RAM is newer than the one on disk (or there is no copy on disk yet). */
        var dirty = true
    }

    private val maxResident: Int =
        maxOf(1L, memoryBudgetBytes / TILE_BYTES).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private val slots = HashMap<TileId, Slot>()

    /** Access ordered, so the first entry is the least recently used tile. */
    private val resident = LinkedHashMap<TileId, Tile>(64, 0.75f, true)

    /** Spare tiles, reused instead of allocating new direct buffers. */
    private val pool = ArrayDeque<Tile>()
    private var nextId = 1L

    init {
        spillDir.mkdirs()
    }

    val residentTiles: Int get() = resident.size
    val liveTiles: Int get() = slots.size

    /** Creates a new fully transparent tile with one reference. */
    fun create(): TileId {
        val id = nextId++
        val tile = obtain()
        tile.clear()
        slots[id] = Slot(1)
        resident[id] = tile
        trim()
        return id
    }

    /** Read-only access. */
    fun read(id: TileId): Tile = load(id)

    /** Write access. The tile must have exactly one reference; use [writableId] first otherwise. */
    fun write(id: TileId): Tile {
        val slot = slotOf(id)
        check(slot.refs == 1) { "tile $id is shared (refs=${slot.refs}); call writableId() first" }
        val tile = load(id)
        slot.dirty = true
        return tile
    }

    fun retain(id: TileId) {
        slotOf(id).refs++
    }

    fun refCount(id: TileId): Int = slotOf(id).refs

    /** Drops one reference. When the last one goes, the memory and the spill file are freed. */
    fun release(id: TileId) {
        val slot = slotOf(id)
        slot.refs--
        if (slot.refs > 0) return
        slots.remove(id)
        resident.remove(id)?.let { recycle(it) }
        if (slot.onDisk) fileOf(id).delete()
    }

    /**
     * Copy-on-write. Returns [id] itself when it has a single reference. Otherwise returns the
     * id of a private copy (one reference) and gives up one reference to [id].
     */
    fun writableId(id: TileId): TileId {
        val slot = slotOf(id)
        if (slot.refs == 1) return id
        val source = load(id)
        val copy = obtain()
        copy.copyFrom(source)
        val newId = nextId++
        slots[newId] = Slot(1)
        resident[newId] = copy
        slot.refs--
        trim()
        return newId
    }

    /** Frees all memory and deletes every spill file. */
    override fun close() {
        resident.clear()
        pool.clear()
        slots.clear()
        spillDir.listFiles { f -> f.name.endsWith(".tile") }?.forEach { it.delete() }
    }

    private fun slotOf(id: TileId): Slot = slots[id] ?: error("unknown tile $id")

    private fun fileOf(id: TileId) = File(spillDir, "$id.tile")

    private fun load(id: TileId): Tile {
        resident[id]?.let { return it }
        val slot = slotOf(id)
        check(slot.onDisk) { "tile $id is neither in memory nor on disk" }
        val tile = obtain()
        readFromDisk(id, tile)
        resident[id] = tile
        slot.dirty = false
        trim()
        return tile
    }

    /** Evicts least recently used tiles until the memory budget is met. */
    private fun trim() {
        while (resident.size > maxResident) {
            val iterator = resident.entries.iterator()
            val eldest = iterator.next()
            val id = eldest.key
            val tile = eldest.value
            iterator.remove()
            val slot = slots.getValue(id)
            if (slot.dirty || !slot.onDisk) {
                writeToDisk(id, tile)
                slot.onDisk = true
                slot.dirty = false
            }
            recycle(tile)
        }
    }

    private fun obtain(): Tile = pool.removeLastOrNull() ?: Tile()

    private fun recycle(tile: Tile) {
        if (pool.size < POOL_MAX) pool.addLast(tile)
    }

    private fun writeToDisk(id: TileId, tile: Tile) {
        val buffer = tile.bytes.duplicate()
        buffer.clear()
        RandomAccessFile(fileOf(id), "rw").use { file ->
            val channel = file.channel
            while (buffer.hasRemaining()) channel.write(buffer)
        }
    }

    private fun readFromDisk(id: TileId, tile: Tile) {
        val buffer = tile.bytes.duplicate()
        buffer.clear()
        RandomAccessFile(fileOf(id), "r").use { file ->
            val channel = file.channel
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) error("spill file of tile $id is truncated")
            }
        }
    }

    private companion object {
        const val POOL_MAX = 8
    }
}
