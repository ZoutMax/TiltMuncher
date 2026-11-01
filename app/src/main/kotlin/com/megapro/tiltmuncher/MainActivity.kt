package com.megapro.tiltmuncher

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.megapro.tiltmuncher.audio.Sounds
import com.megapro.tiltmuncher.data.HighScores
import com.megapro.tiltmuncher.game.Game
import com.megapro.tiltmuncher.ui.GameScreen
import com.megapro.tiltmuncher.ui.theme.TiltMuncherTheme

class MainActivity : ComponentActivity(), SensorEventListener {
  private lateinit var sensorManager: SensorManager
  private var sensor: Sensor? = null

  // Set when falling back to the raw accelerometer, which needs smoothing
  private var smooth = false
  private var fx = 0f
  private var fy = 0f
  private var fz = 0f

  private val sounds by lazy { Sounds(this) }
  private val highScores by lazy { HighScores(this) }
  private val game by lazy { Game(::vibrate, sounds).also { it.loadBest(highScores.best) } }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

    sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
    sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    if (sensor == null) {
      sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
      smooth = true
    }

    setContent { TiltMuncherTheme(darkTheme = true) { GameScreen(game, highScores) } }
  }

  override fun onResume() {
    super.onResume()
    sensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
  }

  override fun onPause() {
    super.onPause()
    sensorManager.unregisterListener(this)
    game.pauseIfPlaying()
    sounds.pauseAll()
  }

  override fun onDestroy() {
    super.onDestroy()
    sounds.release()
  }

  override fun onSensorChanged(event: SensorEvent) {
    if (smooth) {
      fx += (event.values[0] - fx) * 0.2f
      fy += (event.values[1] - fy) * 0.2f
      fz += (event.values[2] - fz) * 0.2f
      game.onSensor(fx, fy, fz)
    } else {
      game.onSensor(event.values[0], event.values[1], event.values[2])
    }
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

  private fun vibrate(ms: Long) {
    val vibrator: Vibrator =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
      } else {
        @Suppress("DEPRECATION")
        getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
      }
    vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
  }
}
