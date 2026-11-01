package com.megapro.tiltmuncher.game

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

enum class Phase { READY, PLAYING, PAUSED, DYING, LEVEL_CLEAR, GAME_OVER }

/** Something that moves cell to cell. Position is the cell being left plus progress toward the next one. */
open class Mover(startX: Int, startY: Int) {
    var x = startX
    var y = startY
    var dir = Dir.NONE
    var progress = 0f

    /** Render position in cell units (centre of the cell is +0.5). */
    val px: Float get() = x + 0.5f + dir.dx * progress
    val py: Float get() = y + 0.5f + dir.dy * progress

    /** The tile the mover is visually on. */
    val tileX: Int get() = Maze.wrapX(floor(px).toInt())
    val tileY: Int get() = floor(py).toInt()

    fun place(cx: Int, cy: Int) {
        x = cx; y = cy; dir = Dir.NONE; progress = 0f
    }

    fun reverse() {
        if (dir == Dir.NONE) return
        if (progress > 0f) {
            x = Maze.wrapX(x + dir.dx); y += dir.dy
            progress = 1f - progress
        }
        dir = dir.opposite
    }
}

/** NEST: waiting in the house. ACTIVE: out and about (or on the way out). EATEN: eyes running home. */
enum class SentinelState { NEST, ACTIVE, EATEN }

class Sentinel(val id: Int, val home: Cell) : Mover(home.x, home.y) {
    var state = SentinelState.NEST
    var frightened = false

    /** For EATEN: the eyes have reached the gate and are descending into the house. */
    var reachedGate = false

    /** Seconds spent as eyes; a safety net sends them home if the trip takes absurdly long. */
    var eyesTime = 0f
}

private enum class Mode { SCATTER, CHASE }

/** The arcade's switches: starting lives, the score for a bonus life (0 = none), and difficulty. */
data class GameSettings(val startLives: Int = 3, val extraLifeAt: Int = 10_000, val hard: Boolean = false)

/** Bonus items by level, with their point values. */
enum class Fruit(val points: Int) {
    CHERRY(100), STRAWBERRY(300), ORANGE(500), APPLE(700), MELON(1000), STAR(2000), KEY(5000);

    companion object {
        fun forLevel(level: Int): Fruit = when {
            level <= 1 -> CHERRY
            level == 2 -> STRAWBERRY
            level <= 4 -> ORANGE
            level <= 6 -> APPLE
            level <= 8 -> MELON
            level <= 12 -> STAR
            else -> KEY
        }
    }
}

/** Per-level tuning: speeds are fractions of [Game.MAX_SPEED]. */
private class LevelSpec(
    val player: Float, val ghost: Float, val tunnel: Float,
    val frightPlayer: Float, val frightGhost: Float,
    val elroy1: Float, val elroy2: Float, val elroyDots1: Int, val elroyDots2: Int,
    val frightSeconds: Float, val flashes: Int,
    /** Scatter, chase, scatter, chase... in seconds; after the last entry chase lasts forever. */
    val schedule: FloatArray,
    /** Dots that must be eaten before the second and third Sentinels may leave the house. */
    val exitDots: IntArray,
    /** Seconds without eating a dot after which the next waiting Sentinel is released anyway. */
    val exitTimeout: Float,
)

private fun levelSpec(level: Int): LevelSpec {
    val l = level.coerceAtLeast(1)
    val frightTable = floatArrayOf(6f, 5f, 4f, 3f, 2f, 5f, 2f, 2f, 1f, 5f, 2f, 1f, 1f, 3f, 1f, 1f, 0f, 1f, 0f)
    val flashTable = intArrayOf(5, 5, 5, 5, 5, 5, 5, 5, 3, 5, 5, 3, 3, 5, 3, 3, 0, 3, 0)
    val fi = (l - 1).coerceAtMost(frightTable.size - 1)
    // Dot thresholds are defined for a 244-dot maze; scale them to this one
    val scale = Maze.totalPellets / 244f
    val elroy = when {
        l == 1 -> 20; l == 2 -> 30; l <= 5 -> 40; l <= 8 -> 50; l <= 11 -> 60; l <= 14 -> 80; l <= 18 -> 100; else -> 120
    }
    val schedule = when {
        l == 1 -> floatArrayOf(7f, 20f, 7f, 20f, 5f, 20f, 5f)
        l <= 4 -> floatArrayOf(7f, 20f, 7f, 20f, 5f, 1033f, 1f / 60f)
        else -> floatArrayOf(5f, 20f, 5f, 20f, 5f, 1037f, 1f / 60f)
    }
    val exit = when {
        l == 1 -> intArrayOf(30, 60); l == 2 -> intArrayOf(0, 50); else -> intArrayOf(0, 0)
    }.map { (it * scale).toInt() }.toIntArray()
    val d1 = (elroy * scale).toInt()
    val d2 = (elroy * scale / 2f).toInt()
    val fs = frightTable[fi]
    val fl = flashTable[fi]
    return when {
        l == 1 -> LevelSpec(.80f, .75f, .40f, .90f, .50f, .80f, .85f, d1, d2, fs, fl, schedule, exit, 4f)
        l <= 4 -> LevelSpec(.90f, .85f, .45f, .95f, .55f, .90f, .95f, d1, d2, fs, fl, schedule, exit, 4f)
        l <= 20 -> LevelSpec(1f, .95f, .50f, 1f, .60f, 1f, 1.05f, d1, d2, fs, fl, schedule, exit, 3f)
        else -> LevelSpec(.90f, .95f, .50f, .90f, .60f, 1f, 1.05f, d1, d2, 0f, 0, schedule, exit, 3f)
    }
}

class Game(
    private val haptic: (Long) -> Unit = {},
    private val sounds: GameSounds = GameSounds.None,
    var settings: GameSettings = GameSettings(),
) {
    // Snapshot state read by the HUD
    var score by mutableIntStateOf(0); private set
    var best by mutableIntStateOf(0); private set
    var lives by mutableIntStateOf(settings.startLives); private set
    var level by mutableIntStateOf(1); private set
    var phase by mutableStateOf(Phase.READY); private set
    var tilt by mutableStateOf(Dir.NONE); private set

    /** Increments for every new game, so the UI can tell one game over from the next. */
    var roundId by mutableIntStateOf(0); private set

    val player = Mover(Maze.playerStart.x, Maze.playerStart.y)
    val sentinels = List(4) { i -> Sentinel(i, Maze.spawns[i % Maze.spawns.size]) }
    var pellets = Maze.freshPellets(); private set
    var pelletsLeft = Maze.totalPellets; private set

    var time = 0f; private set
    var phaseTime = 0f; private set
    var surgeTime = 0f; private set
    var surgeFlashing = false; private set

    /** True during the start-of-round pause, when the big READY! is shown. */
    val readyShowing: Boolean get() = phase == Phase.PLAYING && freeze > 0f

    /** 0 = gate closed, 1 = fully open; eased toward open while a Sentinel is passing through. */
    var gateAnim = 0f; private set

    /** Last direction the player actually moved in; used to orient the face while stopped. */
    var facing = Dir.RIGHT; private set

    /** Bonus item currently on the maze, if any; it sits on the start tile. */
    var bonus: Fruit? = null; private set
    var bonusTimer = 0f; private set

    /** A score shown briefly on the maze (caught Sentinel or collected bonus), in cell coordinates. */
    var popupText = ""; private set
    var popupX = 0f; private set
    var popupY = 0f; private set
    var popupTime = 0f; private set

    /** During the freeze after a catch the Muncher and the caught Sentinel are hidden. */
    var hiddenPlayer = false; private set
    var hiddenSentinel: Sentinel? = null; private set

    private var extraLifeAwarded = false

    private var dotsThisLevel = 0
    private var bonusShown = 0

    private var spec = levelSpec(1)
    private var mode = Mode.SCATTER
    private var modeIndex = 0
    private var modeTime = 0f
    private var eatChain = 0
    private var wanted = Dir.NONE
    private var freeze = 0f
    private var dotPause = 0f

    // House release bookkeeping: the next Sentinel in line counts dots on its own counter,
    // or everyone shares one global count for the first release after a lost life
    private var exitCounter = 0
    private var dotsThisLife = 0
    private var afterDeath = false
    private var noDotTimer = 0f

    // Audio bookkeeping
    private var sparkAlt = false
    private var loopKind = LoopKind.NONE
    private var loopRate = 1f

    // Tilt input. Recent gravity readings are kept so calibration can average them; steering is
    // measured as tilt angles relative to the neutral grip, which keeps the sensitivity the same
    // whether the phone lies flat or is held upright.
    private val recent = Array(RECENT) { FloatArray(3) }
    private var recentCount = 0
    private var recentNext = 0
    private var rawX = 0f
    private var rawY = 0f
    private var rawZ = 1f
    private var haveRaw = false
    private var neutralRoll = 0f  // asin(x / |g|) of the neutral grip, radians
    private var neutralPitch = 0f // atan2(z, y) of the neutral grip, radians
    private val deadzone = Math.toRadians(3.0).toFloat()

    init { resetPositions() }

    fun onSensor(gx: Float, gy: Float, gz: Float) {
        rawX = gx; rawY = gy; rawZ = gz; haveRaw = true
        val slot = recent[recentNext]
        slot[0] = gx; slot[1] = gy; slot[2] = gz
        recentNext = (recentNext + 1) % RECENT
        if (recentCount < RECENT) recentCount++
    }

    /** Take the current grip as neutral, averaging the last few readings to smooth sensor noise. */
    fun calibrate() {
        if (recentCount == 0) return
        var x = 0f; var y = 0f; var z = 0f
        for (i in 0 until recentCount) { x += recent[i][0]; y += recent[i][1]; z += recent[i][2] }
        x /= recentCount; y /= recentCount; z /= recentCount
        neutralRoll = roll(x, y, z)
        neutralPitch = pitch(y, z)
    }

    /** Sideways tilt: the angle gravity makes with the screen's left-right axis. */
    private fun roll(x: Float, y: Float, z: Float): Float {
        val len = sqrt(x * x + y * y + z * z)
        return if (len < 1e-3f) 0f else asin((x / len).coerceIn(-1f, 1f))
    }

    /** Forward/back tilt: how far the top edge is rotated toward or away from the player. */
    private fun pitch(y: Float, z: Float): Float = atan2(z, y)

    fun onSwipe(d: Dir) {
        wanted = d; tilt = d
    }

    fun onTap() {
        when (phase) {
            Phase.READY -> {
                calibrate()
                val newGame = level == 1 && score == 0
                freeze = if (newGame) 2.0f else 1.5f // hold for the jingle on a fresh game
                if (newGame) sounds.startJingle()
                enterPhase(Phase.PLAYING)
            }
            Phase.PLAYING -> enterPhase(Phase.PAUSED)
            Phase.PAUSED -> { calibrate(); enterPhase(Phase.PLAYING) }
            Phase.GAME_OVER -> newGame()
            else -> {}
        }
    }

    /** Seed the HUD's best score from the saved leaderboard. */
    fun loadBest(saved: Int) {
        if (saved > best) best = saved
    }

    fun pauseIfPlaying() {
        if (phase == Phase.PLAYING) enterPhase(Phase.PAUSED)
    }

    fun update(dt: Float) {
        time += dt; phaseTime += dt
        val gateTarget = if (phase == Phase.PLAYING && sentinels.any { usingGate(it) }) 1f else 0f
        gateAnim += (gateTarget - gateAnim) * (dt * 9f).coerceAtMost(1f)
        when (phase) {
            Phase.PLAYING -> play(dt)
            Phase.DYING -> if (phaseTime > 1.6f) {
                if (lives <= 0) enterPhase(Phase.GAME_OVER) else { resetPositions(); freeze = 2f; enterPhase(Phase.PLAYING) }
            }
            Phase.LEVEL_CLEAR -> if (phaseTime > 2.2f) {
                level++
                spec = levelSpec(level)
                pellets = Maze.freshPellets(); pelletsLeft = Maze.totalPellets
                afterDeath = false
                dotsThisLevel = 0; bonusShown = 0
                resetPositions(); freeze = 2f; enterPhase(Phase.PLAYING)
            }
            else -> {}
        }
    }

    private fun enterPhase(p: Phase) {
        phase = p; phaseTime = 0f
        updateLoop()
    }

    /** Keep the background loop in step with the game state: siren while playing, warble during a surge. */
    private fun updateLoop() {
        val kind = when {
            phase != Phase.PLAYING || freeze > 0f -> LoopKind.NONE
            surgeTime > 0f -> LoopKind.SURGE
            else -> LoopKind.SIREN
        }
        val eaten = Maze.totalPellets - pelletsLeft
        val stage = (5 * eaten / Maze.totalPellets).coerceIn(0, 4)
        val rate = 1f + 0.12f * stage
        if (kind != loopKind || (kind == LoopKind.SIREN && rate != loopRate)) {
            loopKind = kind; loopRate = rate
            sounds.loop(kind, rate)
        }
    }

    private fun newGame() {
        roundId++
        score = 0; lives = settings.startLives; level = 1
        extraLifeAwarded = false
        spec = levelSpec(1)
        pellets = Maze.freshPellets(); pelletsLeft = Maze.totalPellets
        afterDeath = false
        dotsThisLevel = 0; bonusShown = 0
        resetPositions(); enterPhase(Phase.READY)
    }

    /** Start of a life or level: everyone back to their posts, timers reset. */
    private fun resetPositions() {
        player.place(Maze.playerStart.x, Maze.playerStart.y)
        wanted = Dir.LEFT; tilt = Dir.NONE; facing = Dir.LEFT // sets off to the left on its own
        surgeTime = 0f; surgeFlashing = false; eatChain = 0; dotPause = 0f
        mode = Mode.SCATTER; modeIndex = 0; modeTime = 0f
        dotsThisLife = 0; exitCounter = 0; noDotTimer = 0f
        bonus = null; bonusTimer = 0f; popupTime = 0f
        hiddenPlayer = false; hiddenSentinel = null
        sentinels.forEach { s ->
            s.frightened = false
            s.reachedGate = false
            if (s.id == 0) {
                // The hunter starts outside, just above the gate
                s.place(Maze.gateExit.x, Maze.gateExit.y)
                s.state = SentinelState.ACTIVE
            } else {
                s.place(s.home.x, s.home.y)
                s.state = SentinelState.NEST
            }
        }
    }

    private fun play(dt: Float) {
        readTilt()
        if (freeze > 0f) {
            freeze -= dt
            if (popupTime > 0f) popupTime -= dt
            if (freeze <= 0f) { hiddenPlayer = false; hiddenSentinel = null }
            updateLoop()
            return
        }

        // Frightened timer; the scatter/chase clock pauses while it runs
        if (surgeTime > 0f) {
            surgeTime -= dt
            surgeFlashing = surgeTime < spec.flashes * FLASH_PERIOD
            if (surgeTime <= 0f) {
                surgeTime = 0f; surgeFlashing = false
                sentinels.forEach { it.frightened = false }
            }
        } else {
            advanceModeClock(dt)
        }

        releaseFromHouse(dt)
        updateBonus(dt)
        movePlayer(dt)
        if (phase != Phase.PLAYING) return

        for (s in sentinels) stepSentinel(s, dt)

        for (s in sentinels) {
            if (debugInvincible || s.state != SentinelState.ACTIVE || Maze.isNest(s.x, s.y)) continue
            if (s.tileX == player.tileX && s.tileY == player.tileY) {
                if (s.frightened) eatSentinel(s) else { die(); return }
            }
        }
        updateLoop()
    }

    private fun advanceModeClock(dt: Float) {
        if (modeIndex >= spec.schedule.size) return // endless chase
        modeTime += dt
        if (modeTime >= spec.schedule[modeIndex]) {
            modeTime = 0f
            modeIndex++
            mode = if (modeIndex % 2 == 0) Mode.SCATTER else Mode.CHASE
            // Every mode switch makes the Sentinels turn around
            for (s in sentinels) if (s.state == SentinelState.ACTIVE && !Maze.isNest(s.x, s.y)) s.reverse()
        }
    }

    /** Sentinels leave the house in a fixed order, each once enough dots are eaten or after a quiet spell. */
    private fun releaseFromHouse(dt: Float) {
        noDotTimer += dt
        val next = EXIT_ORDER.map { sentinels[it] }.firstOrNull { it.state == SentinelState.NEST } ?: return
        val slot = EXIT_ORDER.indexOf(next.id)
        val limit = if (afterDeath) (GLOBAL_EXIT_DOTS[slot] * Maze.totalPellets / 244f).toInt()
        else if (slot == 0) 0 else spec.exitDots[slot - 1]
        val count = if (afterDeath) dotsThisLife else exitCounter
        if (count >= limit || noDotTimer >= spec.exitTimeout) {
            next.state = SentinelState.ACTIVE
            next.dir = Dir.NONE
            noDotTimer = 0f
            exitCounter = 0 // the next Sentinel in line starts its own count
            if (afterDeath && slot == EXIT_ORDER.size - 1) afterDeath = false
        }
    }

    private fun movePlayer(dt: Float) {
        if (wanted != Dir.NONE && wanted == player.dir.opposite) player.reverse()

        // Cornering: a perpendicular turn may be taken a little before the centre of the next tile
        if (wanted != Dir.NONE && player.dir != Dir.NONE && wanted != player.dir && wanted != player.dir.opposite && player.progress >= 0.55f) {
            val nx = Maze.wrapX(player.x + player.dir.dx)
            val ny = player.y + player.dir.dy
            if (Maze.canPlayerEnter(nx + wanted.dx, ny + wanted.dy)) {
                player.x = nx; player.y = ny; player.progress = 0f; player.dir = wanted
            }
        }

        // Eating a dot costs a frame of movement, so a trail of dots slows the player slightly
        var moving = dt
        if (dotPause > 0f) {
            val used = min(dotPause, dt)
            dotPause -= used; moving -= used
        }
        val pct = if (surgeTime > 0f) spec.frightPlayer else spec.player
        advance(player, MAX_SPEED * pct * moving) { m -> chooseFor(m, wanted) }
        if (player.dir != Dir.NONE) facing = player.dir
        eatAt(player.px, player.py)
    }

    private fun readTilt() {
        if (!haveRaw) return
        // Angles relative to the neutral grip. Gravity reads positive on whichever axis points
        // up, so lowering the left edge raises x (-> LEFT) and lowering the top edge swings the
        // pitch angle past the neutral one (-> UP).
        val tx = roll(rawX, rawY, rawZ) - neutralRoll
        var ty = pitch(rawY, rawZ) - neutralPitch
        if (ty > Math.PI) ty -= (2 * Math.PI).toFloat() else if (ty < -Math.PI) ty += (2 * Math.PI).toFloat()
        val ax = abs(tx)
        val ay = abs(ty)
        val horizontal = if (tx > 0) Dir.LEFT else Dir.RIGHT
        val vertical = if (ty > 0) Dir.UP else Dir.DOWN
        val steeringHorizontally = wanted == Dir.LEFT || wanted == Dir.RIGHT
        val d = when {
            ax < deadzone && ay < deadzone -> Dir.NONE
            ay < deadzone -> horizontal
            ax < deadzone -> vertical
            // Both axes tilted: stay on the current axis unless the other clearly dominates
            steeringHorizontally -> if (ay > ax * 1.3f) vertical else horizontal
            else -> if (ax > ay * 1.3f) horizontal else vertical
        }
        if (d != Dir.NONE) wanted = d
        tilt = d
    }

    private fun chooseFor(m: Mover, want: Dir): Dir {
        if (want != Dir.NONE && Maze.canPlayerEnter(m.x + want.dx, m.y + want.dy)) return want
        if (m.dir != Dir.NONE && Maze.canPlayerEnter(m.x + m.dir.dx, m.y + m.dir.dy)) return m.dir
        return Dir.NONE
    }

    /** Move [distance] cells along the current direction, re-choosing at every cell centre. */
    private fun advance(m: Mover, distance: Float, choose: (Mover) -> Dir) {
        var left = distance
        if (m.dir == Dir.NONE) {
            m.dir = choose(m)
            if (m.dir == Dir.NONE) return
        }
        while (left > 0f) {
            val toNext = 1f - m.progress
            if (left < toNext) { m.progress += left; return }
            left -= toNext
            m.x = Maze.wrapX(m.x + m.dir.dx); m.y += m.dir.dy; m.progress = 0f
            val d = choose(m)
            if (d == Dir.NONE) { m.dir = Dir.NONE; return }
            m.dir = d
        }
    }

    private fun eatAt(px: Float, py: Float) {
        val cx = Maze.wrapX(floor(px).toInt())
        val cy = floor(py).toInt().coerceIn(0, Maze.height - 1)
        when (pellets[cy][cx]) {
            1 -> {
                pellets[cy][cx] = 0; pelletsLeft--; addScore(10)
                dotPause += 1f / 60f
                sounds.spark(sparkAlt); sparkAlt = !sparkAlt
            }
            2 -> {
                pellets[cy][cx] = 0; pelletsLeft--; addScore(50)
                dotPause += 3f / 60f
                sounds.surgePickup()
                startSurge()
            }
            else -> return
        }
        dotsThisLife++
        exitCounter++
        dotsThisLevel++
        noDotTimer = 0f
        if (bonusShown < BONUS_AT.size && dotsThisLevel >= (BONUS_AT[bonusShown] * Maze.totalPellets / 244f).toInt()) {
            bonus = Fruit.forLevel(level)
            bonusTimer = 9.5f
            bonusShown++
        }
        if (pelletsLeft == 0) {
            sounds.levelClear()
            enterPhase(Phase.LEVEL_CLEAR)
        }
    }

    private fun updateBonus(dt: Float) {
        if (popupTime > 0f) popupTime -= dt
        val fruit = bonus ?: return
        bonusTimer -= dt
        if (bonusTimer <= 0f) { bonus = null; return }
        if (player.tileX == Maze.playerStart.x && player.tileY == Maze.playerStart.y) {
            addScore(fruit.points)
            showPopup("${fruit.points}", Maze.playerStart.x + 0.5f, Maze.playerStart.y + 0.5f, 1.5f)
            bonus = null
            haptic(30)
            sounds.bonus()
        }
    }

    private fun showPopup(text: String, x: Float, y: Float, seconds: Float) {
        popupText = text; popupX = x; popupY = y; popupTime = seconds
    }

    private fun addScore(points: Int) {
        score += points
        if (score > best) best = score
        if (!extraLifeAwarded && settings.extraLifeAt > 0 && score >= settings.extraLifeAt) {
            extraLifeAwarded = true
            lives++
            sounds.extraLife()
        }
    }

    private fun startSurge() {
        val seconds = spec.frightSeconds - (if (settings.hard) 1f else 0f)
        if (seconds <= 0f) return
        surgeTime = seconds
        surgeFlashing = false
        eatChain = 0
        for (s in sentinels) {
            if (s.state == SentinelState.EATEN) continue
            s.frightened = true
            if (s.state == SentinelState.ACTIVE && !Maze.isNest(s.x, s.y)) s.reverse()
        }
    }

    private fun eatSentinel(s: Sentinel) {
        val points = 200 shl eatChain.coerceAtMost(3) // 200, 400, 800, 1600
        addScore(points)
        eatChain++
        showPopup("$points", s.px, s.py, 1f)
        hiddenPlayer = true
        hiddenSentinel = s
        s.state = SentinelState.EATEN
        s.frightened = false
        s.reachedGate = false
        s.eyesTime = 0f
        s.dir = Dir.NONE // the eyes may set off in any direction, including back the way it came
        freeze = 1f
        haptic(40)
        sounds.sentinelEaten()
    }

    private fun die() {
        lives--
        afterDeath = true
        haptic(250)
        enterPhase(Phase.DYING)
        sounds.death()
    }

    /** Test hook: the player cannot be caught. */
    internal var debugInvincible = false

    /** Test hook: put a Sentinel at a cell and treat it as caught there. */
    internal fun debugCatchAt(id: Int, x: Int, y: Int) {
        val s = sentinels[id]
        s.state = SentinelState.ACTIVE
        s.place(x, y)
        eatSentinel(s)
    }

    private fun stepSentinel(s: Sentinel, dt: Float) {
        if (s.state == SentinelState.NEST) return
        val inNest = Maze.isNest(s.x, s.y)
        val orangeOut = sentinels[3].state != SentinelState.NEST
        val hardDots = if (settings.hard) 2 else 1
        val pct = when {
            s.state == SentinelState.EATEN -> 1.6f
            inNest -> 0.5f
            Maze.isTunnel(s.x, s.y) -> spec.tunnel
            s.frightened -> spec.frightGhost
            s.id == 0 && orangeOut && pelletsLeft <= spec.elroyDots2 * hardDots -> spec.elroy2
            s.id == 0 && orangeOut && pelletsLeft <= spec.elroyDots1 * hardDots -> spec.elroy1
            else -> spec.ghost
        }
        val hardBoost = if (settings.hard && s.state == SentinelState.ACTIVE && !inNest && !s.frightened) 1.05f else 1f
        advance(s, MAX_SPEED * pct * hardBoost * dt) { chooseSentinel(s) }

        if (s.state == SentinelState.EATEN) {
            s.eyesTime += dt
            if (!s.reachedGate && s.x == Maze.gateExit.x && s.y == Maze.gateExit.y) s.reachedGate = true
            if (s.eyesTime > 15f) { s.place(s.home.x, s.home.y); s.reachedGate = true }
            if (s.reachedGate && s.x == s.home.x && s.y == s.home.y) {
                // Home again: revive and head straight back out
                s.state = SentinelState.ACTIVE
                s.reachedGate = false
                s.dir = Dir.NONE
            }
        }
    }

    private fun chooseSentinel(s: Sentinel): Dir {
        val inNest = Maze.isNest(s.x, s.y)
        val returning = s.state == SentinelState.EATEN
        val mayEnterNest = inNest || (returning && s.reachedGate)
        val open = DECISION_ORDER.filter { d ->
            Maze.canSentinelEnter(s.x + d.dx, s.y + d.dy) && (mayEnterNest || !Maze.isNest(s.x + d.dx, s.y + d.dy))
        }
        var options = open.filter { it != s.dir.opposite }
        if (options.isEmpty()) options = open
        if (options.isEmpty()) return Dir.NONE

        if (returning) {
            // Eyes take the true shortest route: above the gate, down through it, then to their spot.
            // (The nearest-turn rule below can orbit a block forever in this maze.)
            val target = when {
                !s.reachedGate -> Maze.gateExit
                !inNest -> Maze.gate[Maze.gate.size / 2]
                else -> s.home
            }
            val d = pathDir(Cell(s.x, s.y), target, mayEnterNest)
            return if (d != Dir.NONE) d else options.first()
        }
        val target: Cell = when {
            inNest -> Maze.gateExit
            s.frightened -> return options.random()
            mode == Mode.SCATTER -> scatterCorner(s)
            else -> chaseTarget(s)
        }
        // Ties fall to the earlier entry in DECISION_ORDER: up, left, down, right
        return options.minBy { d -> dist2(s.x + d.dx, s.y + d.dy, target.x, target.y) }
    }

    /** First step of the shortest path from [from] to [to] (breadth-first, tunnel wrap included). */
    private fun pathDir(from: Cell, to: Cell, mayEnterNest: Boolean): Dir {
        if (from == to) return Dir.NONE
        val prev = HashMap<Cell, Cell>()
        val queue = ArrayDeque<Cell>()
        queue.add(from); prev[from] = from
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (c == to) break
            for (d in DECISION_ORDER) {
                val n = Cell(Maze.wrapX(c.x + d.dx), c.y + d.dy)
                if (n.y !in 0 until Maze.height || !Maze.canSentinelEnter(n.x, n.y)) continue
                if (!mayEnterNest && Maze.isNest(n.x, n.y)) continue
                if (n in prev) continue
                prev[n] = c; queue.add(n)
            }
        }
        if (to !in prev) return Dir.NONE
        var cur = to
        while (prev[cur] != from) cur = prev.getValue(cur)
        return DECISION_ORDER.first { Maze.wrapX(from.x + it.dx) == cur.x && from.y + it.dy == cur.y }
    }

    private fun scatterCorner(s: Sentinel): Cell = when (s.id) {
        0 -> Cell(Maze.width - 3, -2)              // red: top right
        2 -> Cell(2, -2)                           // pink: top left
        1 -> Cell(Maze.width - 1, Maze.height + 1) // teal: bottom right
        else -> Cell(0, Maze.height + 1)           // orange: bottom left
    }

    /** Red hunts the player; pink aims ahead; teal triangulates off red; orange keeps its distance. */
    private fun chaseTarget(s: Sentinel): Cell {
        val px = player.tileX
        val py = player.tileY
        val d = if (player.dir == Dir.NONE) facing else player.dir
        return when (s.id) {
            0 -> Cell(px, py)
            2 -> {
                var tx = px + 4 * d.dx
                val ty = py + 4 * d.dy
                if (d == Dir.UP) tx -= 4 // the classic quirk: "ahead" when facing up also drifts left
                Cell(tx, ty)
            }
            1 -> {
                var mx = px + 2 * d.dx
                val my = py + 2 * d.dy
                if (d == Dir.UP) mx -= 2
                val red = sentinels[0]
                Cell(2 * mx - red.tileX, 2 * my - red.tileY)
            }
            else -> if (dist2(s.tileX, s.tileY, px, py) > 64) Cell(px, py) else scatterCorner(s)
        }
    }

    /** A Sentinel is using the gate when it is leaving the house or returning as eyes, and is close to it. */
    private fun usingGate(s: Sentinel): Boolean {
        val passing = (s.state == SentinelState.ACTIVE && Maze.isNest(s.x, s.y)) ||
            (s.state == SentinelState.EATEN && s.reachedGate)
        return passing && nearGate(s)
    }

    private fun nearGate(s: Sentinel): Boolean {
        val g = Maze.gate[Maze.gate.size / 2]
        return dist2(s.px, s.py, g.x + 0.5f, g.y + 0.5f) < 1.4f * 1.4f
    }

    private fun dist2(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        return dx * dx + dy * dy
    }

    private fun dist2(ax: Int, ay: Int, bx: Int, by: Int): Int {
        val dx = ax - bx
        val dy = ay - by
        return dx * dx + dy * dy
    }

    companion object {
        /** Full speed in cells per second; every other speed is a percentage of this. */
        const val MAX_SPEED = 7.0f

        /** Pink, teal, orange leave the house in this order (red starts outside). */
        private val EXIT_ORDER = intArrayOf(2, 1, 3)

        /** After a lost life, the same three are released at these dot counts instead. */
        private val GLOBAL_EXIT_DOTS = intArrayOf(7, 17, 32)

        private val DECISION_ORDER = listOf(Dir.UP, Dir.LEFT, Dir.DOWN, Dir.RIGHT)

        /** Gravity readings averaged for calibration (about a quarter second at game rate). */
        private const val RECENT = 12

        /** Dots eaten (on a 244-dot maze) at which the two bonus items of a level appear. */
        private val BONUS_AT = intArrayOf(70, 170)

        /** One white/blue flash cycle at the end of a surge, in seconds. */
        const val FLASH_PERIOD = 0.466f
    }
}
