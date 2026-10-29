package app.tilepaint.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TileStoreTest {

    @Test
    fun evictsToDiskAndReloadsWithoutLosingPixels() {
        TestEnv(budgetTiles = 2).use { env ->
            val store = env.store
            val ids = (0 until 6).map { i ->
                val id = store.create()
                store.write(id)[i, i] = 0xFF000000.toInt() or (i + 1)
                id
            }
            assertTrue(store.residentTiles <= 2, "resident=${store.residentTiles}")
            assertEquals(6, store.liveTiles)
            assertTrue(env.dir.listFiles()!!.isNotEmpty())
            ids.forEachIndexed { i, id ->
                assertEquals(0xFF000000.toInt() or (i + 1), store.read(id)[i, i])
            }
        }
    }

    @Test
    fun releaseDeletesTheSpillFile() {
        TestEnv(budgetTiles = 1).use { env ->
            val a = env.store.create()
            val b = env.store.create() // evicts a to disk
            assertEquals(1, env.dir.listFiles()!!.size)
            env.store.release(a)
            assertEquals(0, env.dir.listFiles()!!.size)
            env.store.release(b)
            assertEquals(0, env.store.liveTiles)
        }
    }

    @Test
    fun copyOnWriteKeepsTheOriginalIntact() {
        TestEnv().use { env ->
            val store = env.store
            val a = store.create()
            store.write(a)[0, 0] = 0xFF112233.toInt()
            store.retain(a)
            val b = store.writableId(a)
            assertNotEquals(a, b)
            store.write(b)[0, 0] = 0xFF445566.toInt()
            assertEquals(0xFF112233.toInt(), store.read(a)[0, 0])
            assertEquals(0xFF445566.toInt(), store.read(b)[0, 0])
            assertEquals(1, store.refCount(a))
            assertEquals(1, store.refCount(b))
        }
    }

    @Test
    fun unsharedTileIsWrittenInPlace() {
        TestEnv().use { env ->
            val a = env.store.create()
            assertEquals(a, env.store.writableId(a))
        }
    }

    @Test
    fun writingASharedTileIsRejected() {
        TestEnv().use { env ->
            val a = env.store.create()
            env.store.retain(a)
            assertFailsWith<IllegalStateException> { env.store.write(a) }
        }
    }
}
