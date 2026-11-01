package com.megapro.tiltmuncher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.megapro.tiltmuncher.data.HighScores
import com.megapro.tiltmuncher.data.ScoreEntry
import com.megapro.tiltmuncher.game.Dir
import com.megapro.tiltmuncher.game.Fruit
import com.megapro.tiltmuncher.game.Game
import com.megapro.tiltmuncher.game.Maze
import com.megapro.tiltmuncher.game.Phase
import com.megapro.tiltmuncher.game.Sentinel
import com.megapro.tiltmuncher.game.SentinelState
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sin

private val Bg = Color(0xFF070B16)
private val Wall = Color(0xFF2F55FF)
private val Gate = Color(0xFFFFB3C7)
private val Dot = Color(0xFFF3ECDC)
private val Label = Color(0xFF8FA3FF)
private val Spark = Color(0xFFFFE08A)
private val Ready = Color(0xFFFFEB3B)
private val Surge = Color(0xFF7CF5FF)
private val Frightened = Color(0xFF3B4CCA)
private val SentinelColors = listOf(
    Color(0xFFFF4D5E), Color(0xFF38E0C8), Color(0xFFFF7BE5), Color(0xFFFFA63D),
)

@Composable
fun GameScreen(game: Game, highScores: HighScores) {
    var frame by remember { mutableIntStateOf(0) }
    val textMeasurer = rememberTextMeasurer()

    // Ask for a name once per game when the final score makes the board
    var entryShownFor by remember { mutableIntStateOf(-1) }
    var showEntry by remember { mutableStateOf(false) }
    LaunchedEffect(game.phase, game.roundId) {
        if (game.phase == Phase.GAME_OVER && entryShownFor != game.roundId && highScores.qualifies(game.score)) {
            entryShownFor = game.roundId
            showEntry = true
        }
    }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) game.update(((now - last) / 1_000_000_000f).coerceAtMost(0.05f))
                last = now
                frame++
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .safeDrawingPadding()
            .pointerInput(Unit) { detectTapGestures { game.onTap() } }
            .pointerInput(Unit) {
                // Swipe fallback for devices without a usable motion sensor
                var dx = 0f
                var dy = 0f
                detectDragGestures(
                    onDragStart = { dx = 0f; dy = 0f },
                    onDragEnd = {
                        val d = when {
                            abs(dx) < 40f && abs(dy) < 40f -> Dir.NONE
                            abs(dx) > abs(dy) -> if (dx > 0) Dir.RIGHT else Dir.LEFT
                            else -> if (dy > 0) Dir.DOWN else Dir.UP
                        }
                        if (d != Dir.NONE) game.onSwipe(d)
                    },
                ) { change, drag ->
                    change.consume()
                    dx += drag.x; dy += drag.y
                }
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            Hud(game)
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                @Suppress("UNUSED_EXPRESSION")
                frame // read here so the canvas redraws every frame
                drawGame(game, textMeasurer)
            }
            LevelFruits(game.level)
        }
        Overlay(game, highScores.entries, Modifier.align(Alignment.Center))
    }
    if (showEntry) {
        NameDialog(
            score = game.score, level = game.level, initial = highScores.lastName,
            onSave = { highScores.add(it, game.score, game.level); showEntry = false },
            onSkip = { showEntry = false },
        )
    }
}

@Composable
private fun NameDialog(score: Int, level: Int, initial: String, onSave: (String) -> Unit, onSkip: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text("NEW TOP SCORE", letterSpacing = 2.sp) },
        text = {
            Column {
                Text("Score $score on level $level. Enter your name:")
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= HighScores.MAX_NAME) name = it },
                    singleLine = true,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name) }) { Text("SAVE") } },
        dismissButton = { TextButton(onClick = onSkip) { Text("SKIP") } },
    )
}

@Composable
private fun Board(entries: List<ScoreEntry>) {
    Column(Modifier.padding(top = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("TOP PLAYERS", color = Label, fontSize = 11.sp, letterSpacing = 2.sp)
        entries.forEachIndexed { i, e ->
            Row(Modifier.width(230.dp).padding(top = 5.dp)) {
                Text("${i + 1}.", color = Label, fontSize = 14.sp, modifier = Modifier.width(26.dp))
                Text(e.name, color = Color.White, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
                Text("${e.score}", color = Spark, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Hud(game: Game) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("SCORE", color = Label, fontSize = 11.sp, letterSpacing = 2.sp)
            Text("${game.score}", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("LEVEL ${game.level}", color = Label, fontSize = 11.sp, letterSpacing = 2.sp)
            TiltArrow(game.tilt)
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text("BEST ${game.best}", color = Label, fontSize = 11.sp, letterSpacing = 2.sp)
            Row {
                repeat((game.lives - 1).coerceAtLeast(0)) {
                    Box(Modifier.padding(start = 4.dp, top = 6.dp).size(14.dp).background(Spark, CircleShape))
                }
            }
        }
    }
}

/** Direction read from the tilt: a cyan arrow with a red tip at the pointing end; a dot when level. */
@Composable
private fun TiltArrow(d: Dir) {
    Canvas(Modifier.padding(top = 4.dp).size(26.dp)) {
        val c = center
        if (d == Dir.NONE) {
            drawCircle(Color(0xFF3A4670), size.minDimension * 0.28f, c)
            return@Canvas
        }
        val len = size.minDimension * 0.5f   // centre to tip
        val half = size.minDimension * 0.42f // half of the base
        val tip = Offset(c.x + d.dx * len, c.y + d.dy * len)
        val base = Offset(c.x - d.dx * len * 0.7f, c.y - d.dy * len * 0.7f)
        val side = if (d.dx != 0) Offset(0f, half) else Offset(half, 0f)
        val body = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(base.x + side.x, base.y + side.y)
            lineTo(base.x - side.x, base.y - side.y)
            close()
        }
        drawPath(body, Surge)
        // Red tip: the front third of the arrow
        val k = 0.38f
        val cut = Offset(tip.x + (base.x - tip.x) * k, tip.y + (base.y - tip.y) * k)
        val cutSide = Offset(side.x * k, side.y * k)
        val tipPath = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(cut.x + cutSide.x, cut.y + cutSide.y)
            lineTo(cut.x - cutSide.x, cut.y - cutSide.y)
            close()
        }
        drawPath(tipPath, Color(0xFFFF4D5E))
    }
}

@Composable
private fun Overlay(game: Game, scores: List<ScoreEntry>, modifier: Modifier) {
    val fresh = game.level == 1 && game.score == 0
    val (title, sub) = when (game.phase) {
        Phase.READY ->
            if (fresh) {
                "TILT MUNCHER" to "Hold the phone the way you'll play,\nthen tap to start.\n\nTilt to steer. Tap to pause."
            } else {
                return
            }
        Phase.PAUSED -> "PAUSED" to "Tap to resume\n(tilt recalibrates to how you hold it now)"
        Phase.GAME_OVER -> "GAME OVER" to "Score ${game.score}\nTap to play again"
        Phase.LEVEL_CLEAR -> "MAZE CLEARED" to ""
        else -> return
    }
    Column(
        modifier.background(Color(0xDD070B16), RoundedCornerShape(16.dp)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = Spark, fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
        if (sub.isNotEmpty()) {
            Text(
                sub, color = Color.White, fontSize = 15.sp, lineHeight = 22.sp,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp),
            )
        }
        val showBoard = scores.isNotEmpty() && (game.phase == Phase.GAME_OVER || (game.phase == Phase.READY && fresh))
        if (showBoard) Board(scores.take(5))
    }
}

private fun DrawScope.drawGame(game: Game, textMeasurer: TextMeasurer) {
    val w = Maze.width
    val h = Maze.height
    // Square cells, so every corridor is the same width in both directions
    val cw = min(size.width / w, size.height / h)
    val ch = cw
    val ox = (size.width - cw * w) / 2f
    val oy = (size.height - ch * h) / 2f

    // Walls: rounded hollow outlines. Every wall region is drawn as one rounded slab and then
    // hollowed out with a slightly smaller rounded slab in the background colour.
    val flash = game.phase == Phase.LEVEL_CLEAR && (game.phaseTime * 4).toInt() % 2 == 0
    wallImage.ensure(cw, ch)
    drawImage(
        wallImage.image, topLeft = Offset(ox, oy),
        colorFilter = if (flash) ColorFilter.tint(Color.White) else null,
    )

    // Nest gate: a bar that splits and slides into the walls while a Sentinel passes through
    run {
        val first = Maze.gate.first()
        val last = Maze.gate.last()
        val left = ox + first.x * cw
        val span = (last.x - first.x + 1) * cw
        val half = span / 2f
        val slide = game.gateAnim * half
        val top = oy + (first.y + 0.44f) * ch
        val thick = ch * 0.12f
        drawRect(Gate, Offset(left - slide, top), Size(half, thick))
        drawRect(Gate, Offset(left + half + slide, top), Size(half, thick))
    }

    // Sparks and surges
    val pulse = 0.8f + 0.2f * sin(game.time * 6f)
    for (y in 0 until h) for (x in 0 until w) {
        val c = Offset(ox + (x + 0.5f) * cw, oy + (y + 0.5f) * ch)
        when (game.pellets[y][x]) {
            1 -> drawCircle(Dot, cw * 0.10f, c)
            2 -> {
                drawCircle(Surge.copy(alpha = 0.25f), cw * 0.42f * pulse, c)
                drawCircle(Surge, cw * 0.22f * pulse, c)
            }
        }
    }

    // Bonus item on the start tile, and the points of the last one taken
    val bc = Offset(ox + (Maze.playerStart.x + 0.5f) * cw, oy + (Maze.playerStart.y + 0.5f) * ch)
    game.bonus?.let { fruit ->
        val blink = game.bonusTimer < 2f && (game.bonusTimer * 6).toInt() % 2 == 0
        if (!blink) drawFruit(fruit, bc, cw * 1.1f)
    }
    if (game.popupTime > 0f) {
        val text = textMeasurer.measure(game.popupText, TextStyle(color = Surge, fontSize = 14.sp, fontWeight = FontWeight.Bold))
        val pc = Offset(ox + game.popupX * cw, oy + game.popupY * ch)
        drawText(text, topLeft = Offset(pc.x - text.size.width / 2f, pc.y - text.size.height / 2f))
    }

    for (s in game.sentinels) if (s !== game.hiddenSentinel) drawSentinel(s, game, cw, ch, ox, oy)
    if (!game.hiddenPlayer) drawPlayer(game, cw, ch, ox, oy)

    if (game.readyShowing) {
        val ready = textMeasurer.measure(
            "READY!",
            TextStyle(color = Ready, fontSize = (cw * 0.72f).toSp(), fontWeight = FontWeight.Black, letterSpacing = (cw * 0.06f).toSp()),
        )
        val rc = Offset(ox + Maze.width / 2f * cw, oy + (Maze.readyRow + 0.5f) * ch)
        drawText(ready, topLeft = Offset(rc.x - ready.size.width / 2f, rc.y - ready.size.height / 2f))
    }
}

/** Recent levels' bonus items, shown under the maze as the level indicator. */
@Composable
private fun LevelFruits(level: Int) {
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.Center) {
        val from = (level - 6).coerceAtLeast(1)
        for (l in from..level) {
            val fruit = Fruit.forLevel(l)
            Canvas(Modifier.padding(horizontal = 3.dp).size(22.dp)) { drawFruit(fruit, center, size.minDimension) }
        }
    }
}

/** Simple original fruit icons, drawn in a box of [s] pixels centred on [c]. */
private fun DrawScope.drawFruit(fruit: Fruit, c: Offset, s: Float) {
    val leaf = Color(0xFF4CCB5A)
    val stem = Color(0xFF8D6E3F)
    when (fruit) {
        Fruit.CHERRY -> {
            val r = s * 0.2f
            val a = Offset(c.x - s * 0.2f, c.y + s * 0.18f)
            val b = Offset(c.x + s * 0.2f, c.y + s * 0.25f)
            val top = Offset(c.x + s * 0.12f, c.y - s * 0.38f)
            drawLine(stem, a, top, s * 0.06f, StrokeCap.Round)
            drawLine(stem, b, top, s * 0.06f, StrokeCap.Round)
            drawCircle(Color(0xFFE53935), r, a)
            drawCircle(Color(0xFFE53935), r, b)
            drawCircle(Color.White.copy(alpha = 0.5f), r * 0.3f, Offset(a.x - r * 0.3f, a.y - r * 0.3f))
            drawCircle(Color.White.copy(alpha = 0.5f), r * 0.3f, Offset(b.x - r * 0.3f, b.y - r * 0.3f))
            drawOval(leaf, Offset(top.x, top.y - s * 0.06f), Size(s * 0.22f, s * 0.12f))
        }
        Fruit.STRAWBERRY -> {
            val body = Path().apply {
                moveTo(c.x - s * 0.32f, c.y - s * 0.15f)
                quadraticTo(c.x, c.y - s * 0.45f, c.x + s * 0.32f, c.y - s * 0.15f)
                quadraticTo(c.x + s * 0.3f, c.y + s * 0.35f, c.x, c.y + s * 0.42f)
                quadraticTo(c.x - s * 0.3f, c.y + s * 0.35f, c.x - s * 0.32f, c.y - s * 0.15f)
                close()
            }
            drawPath(body, Color(0xFFE53955))
            for ((dx, dy) in listOf(-0.12f to 0f, 0.12f to 0.02f, 0f to 0.18f, -0.05f to -0.15f, 0.08f to -0.17f)) {
                drawCircle(Color(0xFFFFF59D), s * 0.035f, Offset(c.x + dx * s, c.y + dy * s))
            }
            for (dx in floatArrayOf(-0.16f, 0f, 0.16f)) {
                drawOval(leaf, Offset(c.x + dx * s - s * 0.08f, c.y - s * 0.4f), Size(s * 0.16f, s * 0.14f))
            }
        }
        Fruit.ORANGE -> {
            drawCircle(Color(0xFFFF9800), s * 0.34f, c)
            drawCircle(Color(0xFFFFB74D).copy(alpha = 0.6f), s * 0.12f, Offset(c.x - s * 0.12f, c.y - s * 0.12f))
            drawOval(leaf, Offset(c.x - s * 0.02f, c.y - s * 0.46f), Size(s * 0.22f, s * 0.12f))
        }
        Fruit.APPLE -> {
            drawCircle(Color(0xFFD32F2F), s * 0.3f, Offset(c.x - s * 0.1f, c.y + s * 0.05f))
            drawCircle(Color(0xFFD32F2F), s * 0.3f, Offset(c.x + s * 0.1f, c.y + s * 0.05f))
            drawLine(stem, Offset(c.x, c.y - s * 0.2f), Offset(c.x + s * 0.05f, c.y - s * 0.42f), s * 0.06f, StrokeCap.Round)
            drawOval(leaf, Offset(c.x + s * 0.05f, c.y - s * 0.4f), Size(s * 0.2f, s * 0.11f))
            drawCircle(Color.White.copy(alpha = 0.4f), s * 0.07f, Offset(c.x - s * 0.2f, c.y - s * 0.08f))
        }
        Fruit.MELON -> {
            drawCircle(Color(0xFF2E7D32), s * 0.36f, c)
            for (k in -2..2) {
                val x = c.x + k * s * 0.14f
                drawLine(Color(0xFF66BB6A), Offset(x, c.y - s * 0.3f), Offset(x, c.y + s * 0.3f), s * 0.04f, StrokeCap.Round)
            }
            drawLine(stem, Offset(c.x, c.y - s * 0.36f), Offset(c.x + s * 0.08f, c.y - s * 0.48f), s * 0.05f, StrokeCap.Round)
        }
        Fruit.STAR -> {
            val star = Path()
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) s * 0.42f else s * 0.18f
                val a = (-90.0 + i * 36.0) * Math.PI / 180.0
                val p = Offset(c.x + (r * kotlin.math.cos(a)).toFloat(), c.y + (r * kotlin.math.sin(a)).toFloat())
                if (i == 0) star.moveTo(p.x, p.y) else star.lineTo(p.x, p.y)
            }
            star.close()
            drawPath(star, Color(0xFFFFD54F))
        }
        Fruit.KEY -> {
            val gold = Color(0xFFFFC107)
            drawCircle(gold, s * 0.16f, Offset(c.x, c.y - s * 0.24f), style = Stroke(width = s * 0.08f))
            drawLine(gold, Offset(c.x, c.y - s * 0.08f), Offset(c.x, c.y + s * 0.4f), s * 0.09f, StrokeCap.Round)
            drawLine(gold, Offset(c.x, c.y + s * 0.38f), Offset(c.x + s * 0.16f, c.y + s * 0.38f), s * 0.08f, StrokeCap.Round)
            drawLine(gold, Offset(c.x, c.y + s * 0.24f), Offset(c.x + s * 0.12f, c.y + s * 0.24f), s * 0.08f, StrokeCap.Round)
        }
    }
}

/**
 * The static wall picture, rendered once per cell size into an off-screen image. Every wall cell
 * becomes a rounded slab, bridged to its wall neighbours (wide enough to cover the rounded
 * corners, so runs merge into one shape); a second pass erases a smaller rounded slab from the
 * inside, leaving a rounded outline with hollow blocks and pill-shaped bars.
 */
private class WallImage {
    private var cw = -1f
    private var ch = -1f
    var image: ImageBitmap = ImageBitmap(1, 1)
        private set

    fun ensure(cw: Float, ch: Float) {
        if (cw == this.cw && ch == this.ch) return
        this.cw = cw; this.ch = ch
        val wpx = ceil(cw * Maze.width).toInt().coerceAtLeast(1)
        val hpx = ceil(ch * Maze.height).toInt().coerceAtLeast(1)
        val bmp = ImageBitmap(wpx, hpx)
        val u = min(cw, ch)
        val line = u * 0.11f
        CanvasDrawScope().draw(
            Density(1f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(bmp),
            Size(wpx.toFloat(), hpx.toFloat()),
        ) {
            slabs(Wall, u * 0.06f, u * 0.48f, BlendMode.SrcOver)
            slabs(Color.Black, u * 0.06f + line, u * 0.48f - line, BlendMode.Clear)
        }
        image = bmp
    }

    private fun DrawScope.slabs(color: Color, inset: Float, radius: Float, blend: BlendMode) {
        val body = Size(cw - 2 * inset, ch - 2 * inset)
        val r = CornerRadius(radius.coerceAtLeast(0f))
        val reach = inset + radius
        for (y in 0 until Maze.height) for (x in 0 until Maze.width) {
            if (!Maze.isWall(x, y)) continue
            val left = x * cw
            val top = y * ch
            drawRoundRect(color, Offset(left + inset, top + inset), body, r, blendMode = blend)
            if (x + 1 < Maze.width && Maze.isWall(x + 1, y)) {
                drawRect(color, Offset(left + cw - reach, top + inset), Size(2 * reach, ch - 2 * inset), blendMode = blend)
            }
            if (y + 1 < Maze.height && Maze.isWall(x, y + 1)) {
                drawRect(color, Offset(left + inset, top + ch - reach), Size(cw - 2 * inset, 2 * reach), blendMode = blend)
            }
            // Where four wall cells meet, the two bridges leave a small uncovered square: fill it
            if (x + 1 < Maze.width && y + 1 < Maze.height &&
                Maze.isWall(x + 1, y) && Maze.isWall(x, y + 1) && Maze.isWall(x + 1, y + 1)
            ) {
                drawRect(color, Offset(left + cw - reach, top + ch - reach), Size(2 * reach, 2 * reach), blendMode = blend)
            }
        }
    }
}

private val wallImage = WallImage()

/** Render x positions, duplicated across the tunnel edge so a wrapping mover is drawn on both sides. */
private fun wrapPositions(px: Float): List<Float> = buildList {
    add(px)
    if (px < 1f) add(px + Maze.width)
    if (px > Maze.width - 1f) add(px - Maze.width)
}

/** The Muncher: a glowing orb that chomps, stretches along its travel direction, and blinks. */
private fun DrawScope.drawPlayer(game: Game, cw: Float, ch: Float, ox: Float, oy: Float) {
    val u = cw * 0.95f // character size relative to a corridor cell
    val p = game.player
    var r = u * 0.42f
    var alpha = 1f
    val dying = game.phase == Phase.DYING
    if (dying) {
        val k = (1f - game.phaseTime / 1.6f).coerceIn(0f, 1f)
        r *= k; alpha = k
    }
    if (r < 0.5f) return

    val t = game.time
    val moving = game.phase == Phase.PLAYING && p.dir != Dir.NONE
    val look = game.facing
    val horizontal = look.dx != 0
    val chomp = when {
        dying -> 0.9f
        moving -> 0.55f + 0.45f * sin(t * 18f)
        else -> 0.2f + 0.1f * sin(t * 3f)
    }
    val stretch = if (moving) 1f + 0.07f * sin(t * 18f) else 1f + 0.03f * sin(t * 4f)
    val blink = !dying && (t % 3.7f) < 0.12f
    val ink = Color(0xFF1B1F3B)

    for (px in wrapPositions(p.px)) {
        val c = Offset(ox + px * cw, oy + p.py * ch)
        drawCircle(Spark.copy(alpha = 0.18f * alpha), r * 1.35f * (1f + 0.08f * sin(t * 5f)), c)
        val sx = if (horizontal) stretch else 2f - stretch
        val sy = if (horizontal) 2f - stretch else stretch
        scale(sx, sy, pivot = c) {
            drawCircle(
                Brush.radialGradient(listOf(Color(0xFFFFF59D), Color(0xFFFFB300)), center = c, radius = r),
                r, c, alpha = alpha,
            )
            // Mouth at the front, opening along the travel axis
            val mc = Offset(c.x + look.dx * r * 0.58f, c.y + look.dy * r * 0.58f)
            val along = r * 0.6f * chomp.coerceAtLeast(0.06f)
            val across = r * 0.5f
            val ms = if (horizontal) Size(along, across) else Size(across, along)
            drawOval(Color(0xFF6D3B00), Offset(mc.x - ms.width / 2, mc.y - ms.height / 2), ms, alpha = alpha)
            // Eyes, set back from the mouth, pupils toward the travel direction
            val ex = c.x + look.dx * r * 0.12f
            val ey = c.y + look.dy * r * 0.12f
            val side = if (horizontal) Offset(0f, r * 0.34f) else Offset(r * 0.34f, 0f)
            for (sgn in floatArrayOf(-1f, 1f)) {
                val e = Offset(ex + side.x * sgn, ey + side.y * sgn)
                if (blink) {
                    drawLine(ink, Offset(e.x - r * 0.16f, e.y), Offset(e.x + r * 0.16f, e.y), r * 0.08f, StrokeCap.Round, alpha = alpha)
                } else {
                    drawCircle(Color.White, r * 0.20f, e, alpha = alpha)
                    drawCircle(ink, r * 0.10f, Offset(e.x + look.dx * r * 0.07f, e.y + look.dy * r * 0.07f), alpha = alpha)
                }
            }
        }
    }
}

private fun Color.darken(f: Float) = Color(red * f, green * f, blue * f, alpha)

/** Little capsule-bodied creatures with goggle eyes, hair tufts and waddling feet. */
private fun DrawScope.drawSentinel(s: Sentinel, game: Game, cw: Float, ch: Float, ox: Float, oy: Float) {
    val u = cw * 0.95f // character size relative to a corridor cell
    val base = SentinelColors[s.id % SentinelColors.size]
    val flicker = game.surgeFlashing && (game.surgeTime / (Game.FLASH_PERIOD / 2f)).toInt() % 2 == 1
    val alpha = 1f
    val body = when {
        s.frightened -> if (flicker) Color(0xFFE8EAF6) else Frightened
        else -> base
    }.copy(alpha = alpha)
    val band = body.darken(0.55f)
    val ink = Color(0xFF151A33).copy(alpha = alpha)

    val bw = u * 0.62f
    val bh = u * 0.84f
    val moving = s.state == SentinelState.ACTIVE && s.dir != Dir.NONE
    val wobble = sin(game.time * 11f + s.id * 1.7f)
    val bob = if (moving) wobble * u * 0.025f else 0f
    val look = if (s.dir == Dir.NONE) Dir.DOWN else s.dir

    for (px in wrapPositions(s.px)) {
        val cx = ox + px * cw
        val cy = oy + s.py * ch + bob
        val left = cx - bw / 2
        val top = cy - bh / 2
        val capsule = Path().apply { addRoundRect(RoundRect(Rect(left, top, left + bw, top + bh), CornerRadius(bw / 2))) }

        if (s.state == SentinelState.EATEN) {
            // Just the eyes hurrying home
            val eyeR = u * 0.125f
            for (side in floatArrayOf(-1f, 1f)) {
                val e = Offset(cx + side * bw * 0.23f, cy - bh * 0.13f)
                drawCircle(Color(0xFF9EA7B3), eyeR * 1.28f, e)
                drawCircle(Color.White, eyeR, e)
                drawCircle(ink, eyeR * 0.5f, Offset(e.x + look.dx * eyeR * 0.4f, e.y + look.dy * eyeR * 0.4f))
            }
            continue
        }

        // Feet, stepping in opposite phase while moving
        val footR = u * 0.075f
        val step = if (moving) wobble * u * 0.04f else 0f
        val footY = top + bh - footR * 0.4f
        drawCircle(ink, footR, Offset(cx - bw * 0.24f, footY + step))
        drawCircle(ink, footR, Offset(cx + bw * 0.24f, footY - step))

        // Body with a darker band around the lower third
        drawPath(capsule, body)
        clipPath(capsule) {
            drawRect(band, Offset(left, top + bh * 0.66f), Size(bw, bh * 0.34f))
        }

        // Hair tufts
        val hairW = u * 0.035f
        for ((dx, lean) in listOf(-0.12f to -0.08f, 0.0f to 0.02f, 0.12f to 0.09f)) {
            val x0 = cx + bw * dx
            drawLine(ink, Offset(x0, top + u * 0.02f), Offset(x0 + bw * lean, top - u * 0.13f), hairW, StrokeCap.Round)
        }

        // Goggle eyes that look where the Sentinel is heading
        val eyeR = u * 0.125f
        val eyeY = cy - bh * 0.13f
        val pupilR = if (s.frightened) eyeR * 0.3f else eyeR * 0.5f
        for (side in floatArrayOf(-1f, 1f)) {
            val e = Offset(cx + side * bw * 0.23f, eyeY)
            drawCircle(Color(0xFF9EA7B3).copy(alpha = alpha), eyeR * 1.28f, e)
            drawCircle(Color.White.copy(alpha = alpha), eyeR, e)
            drawCircle(ink, pupilR, Offset(e.x + look.dx * eyeR * 0.4f, e.y + look.dy * eyeR * 0.4f))
        }

        // Mouth: smile normally, worried "o" when frightened
        val mouthY = cy + bh * 0.16f
        if (s.frightened) {
            drawCircle(ink, u * 0.05f, Offset(cx, mouthY + u * 0.02f), style = Stroke(width = u * 0.03f))
        } else {
            val mw = bw * 0.42f
            drawArc(
                ink, startAngle = 20f, sweepAngle = 140f, useCenter = false,
                topLeft = Offset(cx - mw / 2, mouthY - mw * 0.35f), size = Size(mw, mw * 0.6f),
                style = Stroke(width = u * 0.03f, cap = StrokeCap.Round),
            )
        }
    }
}
