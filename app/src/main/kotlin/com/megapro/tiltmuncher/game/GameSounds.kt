package com.megapro.tiltmuncher.game

enum class LoopKind { NONE, SIREN, SURGE }

/** Sound hooks the game logic calls. Kept free of Android types so the game package stays plain Kotlin. */
interface GameSounds {
    fun spark(alternate: Boolean)
    fun surgePickup()
    fun sentinelEaten()
    fun death()
    fun startJingle()
    fun levelClear()
    fun bonus()
    fun extraLife()

    /** Replace the background loop. [rate] is a playback-speed multiplier (1.0 = normal). */
    fun loop(kind: LoopKind, rate: Float)

    object None : GameSounds {
        override fun spark(alternate: Boolean) = Unit
        override fun surgePickup() = Unit
        override fun sentinelEaten() = Unit
        override fun death() = Unit
        override fun startJingle() = Unit
        override fun levelClear() = Unit
        override fun bonus() = Unit
        override fun extraLife() = Unit
        override fun loop(kind: LoopKind, rate: Float) = Unit
    }
}
