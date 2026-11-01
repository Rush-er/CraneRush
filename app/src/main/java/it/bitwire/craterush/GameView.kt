package it.bitwire.craterush

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import it.bitwire.craterush.game.Engine
import it.bitwire.craterush.game.FrameBuffer
import kotlin.math.abs

class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    val engine = Engine()
    private val fb = FrameBuffer(Engine.W, Engine.H)
    private val lcd = LcdRenderer(Engine.W, Engine.H)
    val beeper = Beeper()

    @Volatile private var running = false
    @Volatile private var surfaceReady = false
    private var thread: Thread? = null

    /** Called on the game thread whenever the hi score improves. */
    var onHiScore: ((Int) -> Unit)? = null

    // ---- input sources, OR-ed together every frame ----
    private var keyLeft = false
    private var keyRight = false
    private var axisLeft = false
    private var axisRight = false
    private val touchZone = HashMap<Int, Zone>()
    private val touchStart = HashMap<Int, Pair<Float, Float>>()
    private val touchSwiped = HashSet<Int>()
    private val swipePx = 36f * resources.displayMetrics.density

    private enum class Zone { LEFT, CENTER, RIGHT, TOP }

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
        keepScreenOn = true
    }

    // ================= lifecycle =================

    override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady = true; startLoop() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceReady = false; stopLoop() }

    fun onResumeGame() { startLoop() }

    fun onPauseGame() {
        synchronized(engine) { engine.pause(); releaseAllInput() }
        stopLoop()
    }

    private fun startLoop() {
        if (running || !surfaceReady) return
        running = true
        thread = Thread(this, "game-loop").apply { start() }
    }

    private fun stopLoop() {
        running = false
        thread?.join(500)
        thread = null
    }

    override fun run() {
        var last = System.nanoTime()
        var lastHi = engine.hiScore
        while (running) {
            val now = System.nanoTime()
            val dt = (now - last) / 1e9f
            last = now

            synchronized(engine) {
                engine.leftHeld = keyLeft || axisLeft || touchZone.values.any { it == Zone.LEFT }
                engine.rightHeld = keyRight || axisRight || touchZone.values.any { it == Zone.RIGHT }
                engine.update(dt)
                engine.draw(fb)
                while (engine.events.isNotEmpty()) beeper.play(engine.events.removeFirst())
                if (engine.hiScore != lastHi) { lastHi = engine.hiScore; onHiScore?.invoke(lastHi) }
            }
            lcd.update(fb, dt)

            val canvas = try { holder.lockHardwareCanvas() } catch (_: Exception) { null }
            if (canvas != null) {
                try { lcd.draw(canvas) } finally { holder.unlockCanvasAndPost(canvas) }
            }

            // Guard against spinning if vsync isn't throttling us (cap ~120 fps).
            val spent = (System.nanoTime() - now) / 1_000_000L
            if (spent < 8) Thread.sleep(8 - spent)
        }
    }

    // ================= touch =================

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val idx = e.actionIndex
        val id = e.getPointerId(idx)
        synchronized(engine) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val x = e.getX(idx); val y = e.getY(idx)
                    if (!engine.isRunning) {
                        engine.pressStart()
                        return true
                    }
                    val zone = zoneFor(x, y)
                    touchStart[id] = x to y
                    when (zone) {
                        Zone.TOP -> engine.togglePause()
                        Zone.CENTER -> engine.pressJump()
                        else -> touchZone[id] = zone
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    for (i in 0 until e.pointerCount) {
                        val pid = e.getPointerId(i)
                        val start = touchStart[pid] ?: continue
                        if (pid in touchSwiped) continue
                        val dx = e.getX(i) - start.first
                        val dy = e.getY(i) - start.second
                        if (-dy > swipePx && -dy > abs(dx)) {
                            engine.pressJump(); touchSwiped += pid
                        } else if (abs(dx) > swipePx && abs(dx) > abs(dy) && touchZone[pid] == null) {
                            engine.pressStep(if (dx > 0) 1 else -1); touchSwiped += pid
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> clearPointer(id)
                MotionEvent.ACTION_CANCEL -> releaseAllInput()
            }
        }
        if (e.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun clearPointer(id: Int) {
        touchZone.remove(id); touchStart.remove(id); touchSwiped.remove(id)
    }

    private fun releaseAllInput() {
        touchZone.clear(); touchStart.clear(); touchSwiped.clear()
        keyLeft = false; keyRight = false; axisLeft = false; axisRight = false
    }

    private fun zoneFor(x: Float, y: Float): Zone {
        val r = lcd.screenRect
        // Tap on the HUD strip (top ~10% of the LCD, or above it) pauses.
        if (!r.isEmpty && y < r.top + r.height() * 0.10f) return Zone.TOP
        val fx = x / width
        return when {
            fx < 0.36f -> Zone.LEFT
            fx > 0.64f -> Zone.RIGHT
            else -> Zone.CENTER
        }
    }

    // ================= keys & gamepad =================

    fun handleKey(code: Int, down: Boolean, repeat: Int): Boolean {
        synchronized(engine) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_4,
                KeyEvent.KEYCODE_NUMPAD_4 -> { keyLeft = down; return true }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_6,
                KeyEvent.KEYCODE_NUMPAD_6 -> { keyRight = down; return true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_SPACE,
                KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_2,
                KeyEvent.KEYCODE_NUMPAD_5, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_DPAD_CENTER -> {
                    if (down && repeat == 0) engine.pressJump(); return true
                }
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_START -> {
                    if (down && repeat == 0) {
                        if (engine.isRunning || engine.state == Engine.State.PAUSED) engine.togglePause()
                        else engine.pressStart()
                    }
                    return true
                }
                KeyEvent.KEYCODE_P -> { if (down && repeat == 0) engine.togglePause(); return true }
                KeyEvent.KEYCODE_M -> { if (down && repeat == 0) beeper.muted = !beeper.muted; return true }
            }
        }
        return false
    }

    fun handleMotion(e: MotionEvent): Boolean {
        if (e.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK ||
            e.actionMasked != MotionEvent.ACTION_MOVE) return false
        val x = e.getAxisValue(MotionEvent.AXIS_X).let { if (abs(it) > 0.5f) it else e.getAxisValue(MotionEvent.AXIS_HAT_X) }
        val y = e.getAxisValue(MotionEvent.AXIS_HAT_Y)
        synchronized(engine) {
            axisLeft = x < -0.5f
            axisRight = x > 0.5f
            if (y < -0.5f) engine.pressJump()
        }
        return true
    }

    fun handleBack(): Boolean = synchronized(engine) {
        if (engine.isRunning) { engine.pause(); true } else false
    }
}
