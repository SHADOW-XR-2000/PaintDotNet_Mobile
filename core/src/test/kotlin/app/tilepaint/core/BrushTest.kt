package app.tilepaint.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrushTest {

    @Test
    fun overBlending() {
        assertEquals(0xFFFF0000.toInt(), Blend.over(0xFF00FF00.toInt(), 0xFFFF0000.toInt()))
        assertEquals(0xFF00FF00.toInt(), Blend.over(0xFF00FF00.toInt(), 0x00FF0000))
        assertEquals(0x80FF0000.toInt(), Blend.over(0, 0x80FF0000.toInt()))

        val gray = Blend.over(0xFFFFFFFF.toInt(), 0x80000000.toInt())
        assertEquals(255, gray ushr 24)
        assertTrue(((gray shr 16) and 0xFF) in 126..128, "got ${gray.toUInt().toString(16)}")
    }

    @Test
    fun stampDrawsARoundDot() {
        TestEnv().use { env ->
            Brush(radius = 5f, color = RED).stamp(env.layer, 50f, 50f)
            assertEquals(RED, env.layer.getPixel(50, 50))
            assertEquals(RED, env.layer.getPixel(53, 50))
            assertEquals(0, env.layer.getPixel(70, 50))
            assertEquals(0, env.layer.getPixel(50, 70))
        }
    }

    @Test
    fun lineLeavesNoGaps() {
        TestEnv().use { env ->
            val brush = Brush(radius = 2f, color = RED)
            brush.stamp(env.layer, 10f, 10f)
            brush.line(env.layer, 10f, 10f, 100f, 10f)
            for (x in 10..100) assertEquals(RED, env.layer.getPixel(x, 10), "x=$x")
            assertEquals(0, env.layer.getPixel(55, 20))
        }
    }

    @Test
    fun eraserClearsPixelsAndLeavesTheRest() {
        TestEnv().use { env ->
            Brush(radius = 5f, color = RED).stamp(env.layer, 50f, 50f)
            Brush(radius = 2f, mode = BrushMode.ERASE).stamp(env.layer, 50f, 50f)
            assertEquals(0, env.layer.getPixel(50, 50) ushr 24)
            // Inside the radius-5 disc but outside the eraser's reach, so it must be untouched.
            assertEquals(RED, env.layer.getPixel(53, 50))
        }
    }

    @Test
    fun erasingAnEmptyLayerAllocatesNothing() {
        TestEnv().use { env ->
            Brush(radius = 20f, mode = BrushMode.ERASE).stamp(env.layer, 300f, 300f)
            assertEquals(0, env.layer.tileCount)
            assertEquals(0, env.store.liveTiles)
        }
    }

    @Test
    fun aStrokeInsideAnEditIsOneUndoStep() {
        TestEnv().use { env ->
            val brush = Brush(radius = 8f, color = RED)
            env.doc.edit("stroke") {
                brush.stamp(env.layer, 100f, 100f)
                brush.line(env.layer, 100f, 100f, 700f, 400f)
            }
            assertTrue(env.layer.tileCount > 1)
            env.doc.history.undo()
            assertEquals(0, env.layer.tileCount)
            assertEquals(0, env.layer.getPixel(100, 100))
            env.doc.history.redo()
            assertEquals(RED, env.layer.getPixel(100, 100))
        }
    }
}
