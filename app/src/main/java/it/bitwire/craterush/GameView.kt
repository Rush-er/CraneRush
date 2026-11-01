package it.bitwire.craterush

import android.content.Context
import android.view.HapticFeedbackConstants
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
    // Recreated whenever the warehouse size (and thus the logical resolution) changes.
    @Volatile private var fb = FrameBuffer(engine.w, engine.h)
    @Volatile private var lcd = LcdRenderer(engine.w, engine.h)
    val beeper = Beeper()

    @Volatile private var running = false
    @Volatile private var surfaceReady = false
    private var thread: Thread? = null

    /** Called on the game thread (holding the engine lock) when settings / scores need saving. */
    var onSave: ((Engine) -> Unit)? = null

    // ---- input sources, OR-ed together every frame ----
    private var keyLeft = false
    private var keyRight = false
    private var axisLeft = false
    private var axisRight = false
    private var hatX = 0
    private var hatY = 0
    private val touchZone = HashMap<Int, Zone>()
    private val touchStart = HashMap<Int, Pair<Float, Float>>()
    private val touchSwiped = HashSet<Int>()
    private val touchButton = HashMap<Int, Controls.Btn>()
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
        while (running) {
            val now = System.nanoTime()
            val dt = (now - last) / 1e9f
            last = now

            val frame: FrameBuffer
            val screen: LcdRenderer
            val pressed: Set<Controls.Btn>
            val buttonsOn: Boolean
            synchronized(engine) {
                engine.leftHeld = keyLeft || axisLeft || touchZone.values.any { it == Zone.LEFT } ||
                    touchButton.values.any { it == Controls.Btn.LEFT }
                engine.rightHeld = keyRight || axisRight || touchZone.values.any { it == Zone.RIGHT } ||
                    touchButton.values.any { it == Controls.Btn.RIGHT }
                engine.update(dt)
                if (fb.w != engine.w || fb.h != engine.h) {
                    fb = FrameBuffer(engine.w, engine.h)
                    lcd = LcdRenderer(engine.w, engine.h)
                }
                frame = fb; screen = lcd
                pressed = touchButton.values.toSet()
                buttonsOn = engine.settings.buttons
                if (!buttonsOn && touchButton.isNotEmpty()) touchButton.clear()
                engine.draw(frame)
                beeper.muted = !engine.settings.sound
                screen.ghosting = engine.settings.ghosting
                while (engine.events.isNotEmpty()) beeper.play(engine.events.removeFirst())
                if (engine.dirty) { engine.dirty = false; onSave?.invoke(engine) }
            }
            screen.update(frame, dt)

            val canvas = try { holder.lockHardwareCanvas() } catch (_: Exception) { null }
            if (canvas != null) {
                try {
                    val controls = Controls.layout(canvas.width, canvas.height, buttonsOn)
                    screen.draw(canvas, controls.lcdArea)
                    controls.draw(canvas, pressed)
                } finally { holder.unlockCanvasAndPost(canvas) }
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
                    val controls = Controls.layout(width, height, engine.settings.buttons)
                    val btn = controls.hit(x, y)
                    if (btn != null) {
                        pressButton(id, btn)
                        return true
                    }
                    if (controls.inControlArea(x, y)) return true   // dead space around the buttons
                    if (engine.inMenu) {
                        val r = lcd.screenRect
                        if (!r.isEmpty) {
                            engine.tap((x - r.left) * engine.w / r.width(), (y - r.top) * engine.h / r.height())
                        }
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
                    val controls = Controls.layout(width, height, engine.settings.buttons)
                    for (i in 0 until e.pointerCount) {
                        val pid = e.getPointerId(i)
                        // Sliding a finger between the left and right buttons switches direction.
                        val held = touchButton[pid]
                        if (held == Controls.Btn.LEFT || held == Controls.Btn.RIGHT) {
                            val now = controls.hit(e.getX(i), e.getY(i))
                            if ((now == Controls.Btn.LEFT || now == Controls.Btn.RIGHT) && now != held) {
                                touchButton[pid] = now
                                if (engine.inMenu) engine.menuAdjust(if (now == Controls.Btn.LEFT) -1 else 1)
                                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            }
                            continue
                        }
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

    private fun pressButton(id: Int, btn: Controls.Btn) {
        touchButton[id] = btn
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        if (engine.inMenu) {
            when (btn) {
                Controls.Btn.LEFT -> engine.menuAdjust(-1)
                Controls.Btn.RIGHT -> engine.menuAdjust(1)
                Controls.Btn.JUMP -> engine.menuSelect()
                Controls.Btn.PAUSE -> if (engine.state == Engine.State.PAUSED) engine.togglePause() else engine.back()
            }
        } else {
            when (btn) {
                Controls.Btn.JUMP -> engine.pressJump()
                Controls.Btn.PAUSE -> engine.togglePause()
                else -> {}   // held directions are read every frame
            }
        }
    }

    private fun clearPointer(id: Int) {
        touchZone.remove(id); touchStart.remove(id); touchSwiped.remove(id); touchButton.remove(id)
    }

    private fun releaseAllInput() {
        touchZone.clear(); touchStart.clear(); touchSwiped.clear(); touchButton.clear()
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

    private val leftKeys = setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4)
    private val rightKeys = setOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6)
    private val upKeys = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2)
    private val downKeys = setOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8)
    private val actionKeys = setOf(
        KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5,
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_DPAD_CENTER,
    )
    private val startKeys = setOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_START)

    fun handleKey(code: Int, down: Boolean, repeat: Int): Boolean {
        synchronized(engine) {
            // Held-direction state is always tracked so releasing a key in a menu doesn't leave it stuck.
            if (code in leftKeys) keyLeft = down
            if (code in rightKeys) keyRight = down
            val first = down && repeat == 0

            if (engine.inMenu) {
                when {
                    code in leftKeys -> { if (down) engine.menuAdjust(-1); return true }
                    code in rightKeys -> { if (down) engine.menuAdjust(1); return true }
                    code in upKeys -> { if (down) engine.menuMove(-1); return true }
                    code in downKeys -> { if (down) engine.menuMove(1); return true }
                    code == KeyEvent.KEYCODE_BUTTON_B -> { if (first) engine.back(); return true }
                    code in actionKeys || code in startKeys -> { if (first) engine.menuSelect(); return true }
                    code == KeyEvent.KEYCODE_P -> { if (first) engine.togglePause(); return true }
                }
            } else {
                when {
                    code in leftKeys || code in rightKeys || code in downKeys -> return true
                    code in upKeys || code in actionKeys -> { if (first) engine.pressJump(); return true }
                    code in startKeys || code == KeyEvent.KEYCODE_P -> { if (first) engine.togglePause(); return true }
                }
            }
            if (code == KeyEvent.KEYCODE_M) { if (first) engine.toggleSound(); return true }
        }
        return false
    }

    fun handleMotion(e: MotionEvent): Boolean {
        if (e.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK ||
            e.actionMasked != MotionEvent.ACTION_MOVE) return false
        val x = e.getAxisValue(MotionEvent.AXIS_X).let { if (abs(it) > 0.5f) it else e.getAxisValue(MotionEvent.AXIS_HAT_X) }
        val y = e.getAxisValue(MotionEvent.AXIS_HAT_Y).let { if (abs(it) > 0.5f) it else e.getAxisValue(MotionEvent.AXIS_Y) }
        val nx = if (x < -0.5f) -1 else if (x > 0.5f) 1 else 0
        val ny = if (y < -0.5f) -1 else if (y > 0.5f) 1 else 0
        synchronized(engine) {
            if (engine.inMenu) {
                axisLeft = false; axisRight = false
                if (nx != 0 && nx != hatX) engine.menuAdjust(nx)
                if (ny != 0 && ny != hatY) engine.menuMove(ny)
            } else {
                axisLeft = nx < 0
                axisRight = nx > 0
                if (ny < 0 && hatY >= 0) engine.pressJump()
            }
            hatX = nx; hatY = ny
        }
        return true
    }

    fun handleBack(): Boolean = synchronized(engine) { engine.back() }
}
