package com.megapro.tiltmuncher.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameTest {
    private fun Game.run(seconds: Float) {
        var t = 0f
        while (t < seconds) { update(1f / 60f); t += 1f / 60f }
    }

    @Test
    fun eyesFindTheirWayHomeFromEveryCorner() {
        val game = Game().apply { debugInvincible = true }
        game.onTap() // title -> playing, with the start pause
        game.run(2.5f)
        assertEquals(Phase.PLAYING, game.phase)

        val corners = listOf(1 to 1, Maze.width - 2 to 1, 1 to Maze.height - 2, Maze.width - 2 to Maze.height - 2)
        corners.forEachIndexed { i, (x, y) -> game.debugCatchAt(i, x, y) }
        assertTrue(game.sentinels.all { it.state == SentinelState.EATEN })

        var t = 0f
        while (t < 14f && game.sentinels.any { it.state == SentinelState.EATEN }) { game.update(1f / 60f); t += 1f / 60f }

        for (s in game.sentinels) {
            assertEquals("Sentinel ${s.id} should be back in play", SentinelState.ACTIVE, s.state)
            assertTrue("Sentinel ${s.id} took ${s.eyesTime}s to get home", s.eyesTime < 10f)
        }
    }

    @Test
    fun sentinelsLeaveTheHouseWithoutDots() {
        val game = Game().apply { debugInvincible = true }
        game.onTap()
        // No dots eaten: the release timeout (4 s each) must still let everyone out
        game.run(2.5f + 3 * 4.5f)
        assertTrue(game.sentinels.none { it.state == SentinelState.NEST })
    }

    /** Feed a steady gravity vector, calibrate on it, then tilt and read the steering direction. */
    private fun steer(neutral: FloatArray, tilted: FloatArray): Dir {
        val game = Game().apply { debugInvincible = true }
        game.onTap(); game.run(2.5f)
        repeat(12) { game.onSensor(neutral[0], neutral[1], neutral[2]) }
        game.calibrate()
        game.onSensor(tilted[0], tilted[1], tilted[2])
        game.update(1f / 60f)
        return game.tilt
    }

    @Test
    fun tiltReadsTheSameWhetherFlatOrUpright() {
        val g = 9.81f
        val flat = floatArrayOf(0f, 0f, g)
        val upright = floatArrayOf(0f, 9.5f, 2.4f) // top up, leaning back a little
        val s = 0.1f // ~6 degrees
        // Flat on a table
        assertEquals(Dir.LEFT, steer(flat, floatArrayOf(g * s, 0f, g)))
        assertEquals(Dir.RIGHT, steer(flat, floatArrayOf(-g * s, 0f, g)))
        assertEquals(Dir.UP, steer(flat, floatArrayOf(0f, -g * s, g)))   // top edge lowered
        assertEquals(Dir.DOWN, steer(flat, floatArrayOf(0f, g * s, g)))  // top edge raised
        // Held upright: forward/back tilt barely changes y, mostly z
        assertEquals(Dir.LEFT, steer(upright, floatArrayOf(g * s, 9.5f, 2.4f)))
        assertEquals(Dir.RIGHT, steer(upright, floatArrayOf(-g * s, 9.5f, 2.4f)))
        assertEquals(Dir.UP, steer(upright, floatArrayOf(0f, 9.2f, 3.4f)))   // top edge away
        assertEquals(Dir.DOWN, steer(upright, floatArrayOf(0f, 9.7f, 1.4f))) // top edge toward
        // Within the deadzone nothing is read
        assertEquals(Dir.NONE, steer(upright, floatArrayOf(0.2f, 9.5f, 2.4f)))
    }

    @Test
    fun backgroundingPausesAndTapResumesWithRecalibration() {
        val game = Game().apply { debugInvincible = true }
        game.onTap(); game.run(2.5f)
        repeat(12) { game.onSensor(0f, 0f, 9.81f) }
        game.calibrate()
        assertEquals(Phase.PLAYING, game.phase)

        game.pauseIfPlaying() // what the Activity does when the app goes to the background
        assertEquals(Phase.PAUSED, game.phase)
        val before = game.score
        game.run(3f)
        assertEquals("nothing moves while paused", before, game.score)
        assertEquals(Phase.PAUSED, game.phase)

        // Resume holding the phone differently: that grip becomes the new neutral
        repeat(12) { game.onSensor(0f, 9.5f, 2.4f) }
        game.onTap()
        assertEquals(Phase.PLAYING, game.phase)
        game.onSensor(0f, 9.5f, 2.4f); game.update(1f / 60f)
        assertEquals("new grip reads as level", Dir.NONE, game.tilt)
        game.onSensor(0.98f, 9.5f, 2.4f); game.update(1f / 60f)
        assertEquals(Dir.LEFT, game.tilt)
    }

    @Test
    fun mazeIsFullyReachableForThePlayer() {
        val start = Maze.playerStart
        val seen = HashSet<Cell>()
        val queue = ArrayDeque(listOf(start))
        seen.add(start)
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            for (d in listOf(Dir.UP, Dir.DOWN, Dir.LEFT, Dir.RIGHT)) {
                val n = Cell(Maze.wrapX(c.x + d.dx), c.y + d.dy)
                if (n.y in 0 until Maze.height && Maze.canPlayerEnter(n.x, n.y) && seen.add(n)) queue.add(n)
            }
        }
        val pellets = Maze.freshPellets()
        for (y in 0 until Maze.height) for (x in 0 until Maze.width) {
            if (pellets[y][x] != 0) assertTrue("pellet at $x,$y unreachable", Cell(x, y) in seen)
        }
    }
}
