package it.bitwire.craterush

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import it.bitwire.craterush.game.Difficulty
import it.bitwire.craterush.game.Engine
import it.bitwire.craterush.game.Warehouse

class MainActivity : ComponentActivity() {

    private lateinit var game: GameView
    private val prefs by lazy { getSharedPreferences("craterush", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        game = GameView(this)
        loadState(game.engine)
        game.onSave = ::saveState
        setContentView(game)
        game.requestFocus()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!game.handleBack()) finish()
            }
        })
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onResume() { super.onResume(); game.onResumeGame() }
    override fun onPause() { game.onPauseGame(); super.onPause() }
    override fun onDestroy() { game.beeper.release(); super.onDestroy() }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        game.handleKey(keyCode, true, event.repeatCount) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        game.handleKey(keyCode, false, 0) || super.onKeyUp(keyCode, event)

    override fun onGenericMotionEvent(event: MotionEvent): Boolean =
        game.handleMotion(event) || super.onGenericMotionEvent(event)

    private fun loadState(e: Engine) = synchronized(e) {
        val s = e.settings
        s.difficulty = Difficulty.entries.getOrElse(prefs.getInt("difficulty", Difficulty.NORMAL.ordinal)) { Difficulty.NORMAL }
        s.warehouse = Warehouse.entries.getOrElse(prefs.getInt("warehouse", Warehouse.LARGE.ordinal)) { Warehouse.LARGE }
        s.sound = prefs.getBoolean("sound", true)
        s.ghosting = prefs.getBoolean("ghosting", true)
        s.buttons = prefs.getBoolean("buttons", true)
        e.bestLevel = prefs.getInt("best_level", 1).coerceIn(1, Engine.MAX_LEVEL)
        s.startLevel = prefs.getInt("start_level", 1).coerceIn(1, e.bestLevel)
        val legacyHi = prefs.getInt("hi_score", 0) // single hi score from version 1.0
        for (d in Difficulty.entries) {
            e.hiScores[d.ordinal] = prefs.getInt("hi_${d.name}", if (d == Difficulty.NORMAL) legacyHi else 0)
        }
        e.applySize()
    }

    private fun saveState(e: Engine) {
        val s = e.settings
        prefs.edit().apply {
            putInt("difficulty", s.difficulty.ordinal)
            putInt("warehouse", s.warehouse.ordinal)
            putBoolean("sound", s.sound)
            putBoolean("ghosting", s.ghosting)
            putBoolean("buttons", s.buttons)
            putInt("best_level", e.bestLevel)
            putInt("start_level", s.startLevel)
            for (d in Difficulty.entries) putInt("hi_${d.name}", e.hiScores[d.ordinal])
        }.apply()
    }
}
