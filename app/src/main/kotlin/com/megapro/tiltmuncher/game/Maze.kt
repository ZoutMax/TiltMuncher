package com.megapro.tiltmuncher.game

enum class Dir(val dx: Int, val dy: Int) {
    NONE(0, 0), UP(0, -1), DOWN(0, 1), LEFT(-1, 0), RIGHT(1, 0);

    val opposite: Dir
        get() = when (this) {
            UP -> DOWN
            DOWN -> UP
            LEFT -> RIGHT
            RIGHT -> LEFT
            NONE -> NONE
        }
}

data class Cell(val x: Int, val y: Int)

/**
 * Original 15x22 layout, mirrored left/right.
 * '#' wall, '.' spark, 'o' surge, ' ' open floor without a spark (tunnel),
 * 'S' nest spawn point, 'D' nest gate (Sentinels only), 'P' player start.
 */
object Maze {
    private val rows = listOf(
        "###############",
        "#.............#",
        "#o###.#.#.###o#",
        "#.............#",
        "#.##.#####.##.#",
        "#.............#",
        "###.###D###.###",
        "   .#SNSNS#.   ",
        "###.#NNSNN#.###",
        "###.#######.###",
        "#.............#",
        "#.###.###.###.#",
        "#.............#",
        "#.##.#####.##.#",
        "#..#...P...#..#",
        "##.#.#####.#.##",
        "#....#...#....#",
        "#.##.#.#.#.##.#",
        "#o.#.......#.o#",
        "#..#.#####.#..#",
        "#.............#",
        "###############",
    )

    val width = rows[0].length
    val height = rows.size

    val playerStart: Cell = find('P').first()
    val spawns: List<Cell> = find('S')
    val gate: List<Cell> = find('D')

    /** The open cell just outside the gate; Sentinels leaving the nest head here first. */
    val gateExit: Cell = gate[gate.size / 2].let { Cell(it.x, it.y - 1) }

    val tunnelRow: Int = rows.indexOfFirst { it[0] == ' ' }

    /** The open row just below the nest, where READY! is shown. */
    val readyRow: Int = (gate.first().y + 1 until height).first { y -> !isNest(width / 2, y) && !isWall(width / 2, y) }
    val totalPellets: Int = rows.sumOf { r -> r.count { it == '.' || it == 'o' } }

    fun wrapX(x: Int): Int = Math.floorMod(x, width)

    private fun at(x: Int, y: Int): Char = if (y !in 0 until height) '#' else rows[y][wrapX(x)]

    fun isWall(x: Int, y: Int): Boolean = at(x, y) == '#'
    fun isNest(x: Int, y: Int): Boolean = at(x, y) in "NSD"
    fun isGate(x: Int, y: Int): Boolean = at(x, y) == 'D'
    fun isTunnel(x: Int, y: Int): Boolean = y == tunnelRow && at(x, y) == ' '

    /** The player may not enter the nest or pass the gate. */
    fun canPlayerEnter(x: Int, y: Int): Boolean = !isWall(x, y) && !isNest(x, y)
    fun canSentinelEnter(x: Int, y: Int): Boolean = !isWall(x, y)

    /** Fresh pellet grid indexed [y][x]: 0 empty, 1 spark, 2 surge. */
    fun freshPellets(): Array<IntArray> = Array(height) { y ->
        IntArray(width) { x ->
            when (rows[y][x]) {
                '.' -> 1
                'o' -> 2
                else -> 0
            }
        }
    }

    private fun find(c: Char): List<Cell> = buildList {
        rows.forEachIndexed { y, r -> r.forEachIndexed { x, ch -> if (ch == c) add(Cell(x, y)) } }
    }
}
