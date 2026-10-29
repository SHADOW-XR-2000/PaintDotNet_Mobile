package app.tilepaint.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HistoryTest {

    @Test
    fun undoAndRedoOfASingleEdit() {
        TestEnv().use { env ->
            env.doc.edit("dot") { env.layer.setPixel(10, 10, RED) }
            assertEquals(RED, env.layer.getPixel(10, 10))

            assertTrue(env.doc.history.undo())
            assertEquals(0, env.layer.getPixel(10, 10))
            assertEquals(0, env.layer.tileCount)

            assertTrue(env.doc.history.redo())
            assertEquals(RED, env.layer.getPixel(10, 10))
        }
    }

    @Test
    fun undoRestoresThePreviousContentsOfATile() {
        TestEnv().use { env ->
            val history = env.doc.history
            env.doc.edit("a") { env.layer.setPixel(5, 5, RED) }
            env.doc.edit("b") {
                env.layer.setPixel(5, 5, BLUE)
                env.layer.setPixel(6, 6, BLUE)
            }
            // The old version of the tile is kept for undo; the layer draws on a copy.
            assertEquals(2, env.store.liveTiles)

            history.undo()
            assertEquals(RED, env.layer.getPixel(5, 5))
            assertEquals(0, env.layer.getPixel(6, 6))

            history.undo()
            assertEquals(0, env.layer.getPixel(5, 5))

            history.redo()
            history.redo()
            assertEquals(BLUE, env.layer.getPixel(5, 5))
            assertEquals(BLUE, env.layer.getPixel(6, 6))
        }
    }

    @Test
    fun budgetDropsTheOldestStepsAndFreesTheirTiles() {
        TestEnv(historyTiles = 2).use { env ->
            env.layer.setPixel(1, 1, RED) // before any edit, so it is not in the history
            for (i in 1..5) env.doc.edit("e$i") { env.layer.setPixel(1, 1, 0xFF000000.toInt() or i) }
            assertEquals(2, env.doc.history.undoDepth)
            // The layer's tile plus the two retained old versions.
            assertEquals(3, env.store.liveTiles)
        }
    }

    @Test
    fun erasingEverythingFreesTheTileAndUndoBringsItBack() {
        TestEnv().use { env ->
            env.layer.setPixel(300, 300, RED)
            env.doc.edit("erase") { env.layer.setPixel(300, 300, 0) }
            assertEquals(0, env.layer.tileCount)

            env.doc.history.undo()
            assertEquals(RED, env.layer.getPixel(300, 300))
            assertEquals(1, env.layer.tileCount)
        }
    }

    @Test
    fun cancelRollsBackAndFreesEverything() {
        TestEnv().use { env ->
            env.doc.history.begin("oops")
            env.layer.setPixel(10, 10, RED)
            env.doc.history.cancel()
            assertEquals(0, env.layer.getPixel(10, 10))
            assertEquals(0, env.layer.tileCount)
            assertEquals(0, env.store.liveTiles)
            assertFalse(env.doc.history.canUndo)
        }
    }

    @Test
    fun aFailingEditIsRolledBack() {
        TestEnv().use { env ->
            val result = runCatching {
                env.doc.edit("boom") {
                    env.layer.setPixel(10, 10, RED)
                    error("boom")
                }
            }
            assertTrue(result.isFailure)
            assertEquals(0, env.layer.getPixel(10, 10))
            assertFalse(env.doc.history.isRecording)
        }
    }

    @Test
    fun aNewEditDiscardsTheRedoStackAndItsTiles() {
        TestEnv().use { env ->
            env.doc.edit("one") { env.layer.setPixel(10, 10, RED) }
            env.doc.history.undo()
            assertTrue(env.doc.history.canRedo)

            env.doc.edit("two") { env.layer.setPixel(700, 700, BLUE) }
            assertFalse(env.doc.history.canRedo)
            assertEquals(1, env.store.liveTiles)
        }
    }

    @Test
    fun anEditThatTouchesNothingIsNotRecorded() {
        TestEnv().use { env ->
            env.doc.edit("nothing") { }
            assertFalse(env.doc.history.canUndo)
        }
    }

    @Test
    fun historySurvivesTilesBeingEvictedToDisk() {
        TestEnv(budgetTiles = 2).use { env ->
            for (i in 0 until 8) {
                env.doc.edit("e$i") { env.layer.setPixel((i % 4) * 256 + 3, (i / 4) * 256 + 3, RED) }
            }
            repeat(8) { assertTrue(env.doc.history.undo()) }
            assertEquals(0, env.layer.tileCount)
            repeat(8) { assertTrue(env.doc.history.redo()) }
            for (i in 0 until 8) assertEquals(RED, env.layer.getPixel((i % 4) * 256 + 3, (i / 4) * 256 + 3))
        }
    }
}
