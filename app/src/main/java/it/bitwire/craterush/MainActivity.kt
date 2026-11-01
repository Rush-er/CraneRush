package it.bitwire.craterush

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {

    private lateinit var game: GameView
    private val prefs by lazy { getSharedPreferences("craterush", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        game = GameView(this)
        game.engine.hiScore = prefs.getInt(KEY_HI, 0)
        game.onHiScore = { hi -> prefs.edit().putInt(KEY_HI, hi).apply() }
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

    companion object { private const val KEY_HI = "hi_score" }
}
