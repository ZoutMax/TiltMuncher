package com.megapro.tiltmuncher.data

import android.content.Context
import androidx.core.content.edit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class ScoreEntry(val name: String, val score: Int, val level: Int, val time: Long)

/** Top-10 table kept in SharedPreferences, one entry per line as name|score|level|time. */
class HighScores(context: Context) {
  private val prefs = context.getSharedPreferences("highscores", Context.MODE_PRIVATE)

  var entries: List<ScoreEntry> by mutableStateOf(load())
    private set

  val best: Int get() = entries.firstOrNull()?.score ?: 0

  var lastName: String
    get() = prefs.getString(KEY_NAME, "") ?: ""
    private set(value) = prefs.edit { putString(KEY_NAME, value) }

  fun qualifies(score: Int): Boolean = score > 0 && (entries.size < MAX || score > entries.last().score)

  /** Inserts the entry and returns its 1-based rank. */
  fun add(name: String, score: Int, level: Int): Int {
    val clean = name.trim().replace('|', ' ').take(MAX_NAME).ifEmpty { "PLAYER" }
    lastName = clean
    val updated = (entries + ScoreEntry(clean, score, level, System.currentTimeMillis()))
      .sortedWith(compareByDescending<ScoreEntry> { it.score }.thenBy { it.time })
      .take(MAX)
    entries = updated
    prefs.edit { putString(KEY_LIST, updated.joinToString("\n") { "${it.name}|${it.score}|${it.level}|${it.time}" }) }
    return updated.indexOfFirst { it.name == clean && it.score == score } + 1
  }

  private fun load(): List<ScoreEntry> =
    (prefs.getString(KEY_LIST, "") ?: "")
      .lines()
      .mapNotNull { line ->
        val p = line.split('|')
        if (p.size != 4) return@mapNotNull null
        ScoreEntry(p[0], p[1].toIntOrNull() ?: return@mapNotNull null, p[2].toIntOrNull() ?: 1, p[3].toLongOrNull() ?: 0L)
      }
      .sortedWith(compareByDescending<ScoreEntry> { it.score }.thenBy { it.time })
      .take(MAX)

  companion object {
    const val MAX = 10
    const val MAX_NAME = 12
    private const val KEY_LIST = "list"
    private const val KEY_NAME = "lastName"
  }
}
