package app.tilepaint.core

import kotlin.test.Test
import kotlin.test.assertEquals

class TileLayerTest {

    @Test
    fun pixelsRoundTripAcrossTileBorders() {
        TestEnv().use { env ->
            val points = listOf(0 to 0, 255 to 255, 256 to 0, 0 to 256, 999 to 999)
            points.forEachIndexed { i, (x, y) -> env.layer.setPixel(x, y, 0xFF000000.toInt() or (i + 1)) }
            points.forEachIndexed { i, (x, y) ->
                assertEquals(0xFF000000.toInt() or (i + 1), env.layer.getPixel(x, y), "pixel $x,$y")
            }
            // (0,0) and (255,255) share a tile; the other three points each need their own.
            assertEquals(4, env.layer.tileCount)
        }
    }

    @Test
    fun outOfBoundsAccessIsIgnored() {
        TestEnv().use { env ->
            env.layer.setPixel(-1, 0, RED)
            env.layer.setPixel(0, -1, RED)
            env.layer.setPixel(1000, 0, RED)
            env.layer.setPixel(0, 1000, RED)
            assertEquals(0, env.layer.tileCount)
            assertEquals(0, env.layer.getPixel(-5, -5))
            assertEquals(0, env.layer.getPixel(5000, 5000))
        }
    }

    @Test
    fun writingTransparencyToAnEmptyLayerAllocatesNothing() {
        TestEnv().use { env ->
            env.layer.setPixel(10, 10, 0)
            assertEquals(0, env.layer.tileCount)
            assertEquals(0, env.store.liveTiles)
        }
    }

    @Test
    fun forEachTileOnlyVisitsExistingTilesInsideTheArea() {
        TestEnv().use { env ->
            env.layer.setPixel(10, 10, RED)    // tile (0,0)
            env.layer.setPixel(600, 10, RED)   // tile (2,0)
            val visited = ArrayList<Pair<Int, Int>>()
            env.layer.forEachTile(IntRect(0, 0, 300, 300)) { tx, ty, _ -> visited.add(tx to ty) }
            assertEquals(listOf(0 to 0), visited)
        }
    }

    @Test
    fun pixelsSurviveEvictionToDisk() {
        TestEnv(budgetTiles = 2).use { env ->
            for (i in 0 until 16) env.layer.setPixel((i % 4) * 256 + 1, (i / 4) * 256 + 1, 0xFF000000.toInt() or i)
            assertEquals(16, env.layer.tileCount)
            for (i in 0 until 16) {
                assertEquals(0xFF000000.toInt() or i, env.layer.getPixel((i % 4) * 256 + 1, (i / 4) * 256 + 1))
            }
        }
    }
}
