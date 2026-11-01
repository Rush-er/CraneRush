package it.bitwire.craterush.game

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Crate-stacking arcade engine. Grid-based, fixed logical resolution, no Android deps.
 *
 * Rules:
 *  - Cranes travel along the top rail and drop crates on random columns.
 *  - The worker walks, jumps one crate high, and pushes a single crate
 *    (only if nothing sits on it and the cell beyond is free).
 *  - A complete bottom row is cleared and everything shifts down.
 *  - A crate landing on the worker, or a column reaching the rail, ends the game.
 */
class Engine(private val rng: Random = Random.Default) {

    companion object {
        const val W = 100
        const val H = 88
        const val COLS = 12
        const val ROWS = 8
        const val CELL = 8
        const val FIELD_X = 2
        const val FIELD_BOTTOM = H          // y of the floor line (exclusive)
        const val FIELD_TOP = FIELD_BOTTOM - ROWS * CELL
        const val RAIL_Y = 8
        const val CRATE_HANG_R = ROWS + 0.5f // crate bottom in rows while hanging under a crane

        const val STEP_TIME = 0.11f
        const val RISE_SPEED = 9f           // rows/s
        const val HANG_TIME = 0.17f
        const val PLAYER_FALL = 10f         // rows/s
        const val CLEAR_TIME = 0.5f
        const val DIE_TIME = 1.6f
        const val LINES_PER_LEVEL = 5
        const val MAX_LEVEL = 9
        private const val EPS = 1e-4f
    }

    enum class State { TITLE, PLAYING, PAUSED, CLEARING, DYING, GAME_OVER }
    enum class Sfx { START, JUMP, PUSH, LAND, CLEAR, LEVEL, DEATH }
    private enum class Phase { GROUND, RISE, HANG, FALL }

    class Loose(var col: Int, var r: Float, val fromCol: Int, var slideT: Float)
    class Crane(var x: Float, val dir: Int, val target: Int, var carrying: Boolean = true)

    // ---- public state ----
    var state = State.TITLE; private set
    var score = 0; private set
    var hiScore = 0
    var lines = 0; private set
    var level = 1; private set
    val events = ArrayDeque<Sfx>()

    // ---- input (set by the view) ----
    var leftHeld = false
    var rightHeld = false
    private var queuedStep = 0
    private var queuedJump = false

    // ---- world ----
    private val grid = Array(COLS) { BooleanArray(ROWS + 4) }
    private val heights = IntArray(COLS)
    private val loose = ArrayList<Loose>()
    private val cranes = ArrayList<Crane>()
    private var spawnT = 0f

    // ---- player ----
    private var px = COLS / 2
    private var py = 0f
    private var phase = Phase.GROUND
    private var riseTo = 0f
    private var hangT = 0f
    private var stepping = false
    private var stepFrom = 0
    private var stepT = 0f
    private var facing = 1
    private var walkFrame = 0

    private var timer = 0f       // state timer (clearing / dying / game over / blink)
    private var blinkT = 0f

    // ---- level tuning ----
    private val craneSpeed get() = 22f + level * 5f                     // px/s
    private val crateFall get() = 5.5f + level * 0.7f                    // rows/s
    private val spawnEvery get() = max(0.8f, 3.4f - level * 0.3f)        // s
    private val maxCranes get() = min(3, 1 + (level - 1) / 3)

    // ================= input API =================

    fun pressStep(dir: Int) { if (state == State.PLAYING) queuedStep = dir }
    fun pressJump() { if (state == State.PLAYING) queuedJump = true else pressStart() }

    fun pressStart() {
        when (state) {
            State.TITLE -> newGame()
            State.GAME_OVER -> if (timer > 0.8f) newGame()
            State.PAUSED -> state = State.PLAYING
            else -> {}
        }
    }

    fun togglePause() {
        state = when (state) {
            State.PLAYING -> State.PAUSED
            State.PAUSED -> State.PLAYING
            else -> state
        }
    }

    fun pause() { if (state == State.PLAYING) state = State.PAUSED }
    val isRunning get() = state == State.PLAYING || state == State.CLEARING || state == State.DYING

    // ================= game flow =================

    fun newGame() {
        grid.forEach { it.fill(false) }
        heights.fill(0)
        loose.clear(); cranes.clear()
        score = 0; lines = 0; level = 1
        px = COLS / 2; py = 0f; phase = Phase.GROUND
        stepping = false; facing = 1
        queuedStep = 0; queuedJump = false
        spawnT = 1.0f
        state = State.PLAYING
        events.addLast(Sfx.START)
    }

    fun update(dtIn: Float) {
        val dt = min(dtIn, 0.05f)
        blinkT += dt
        timer += dt
        when (state) {
            State.PLAYING -> tick(dt)
            State.CLEARING -> if (timer >= CLEAR_TIME) finishClear()
            State.DYING -> if (timer >= DIE_TIME) {
                state = State.GAME_OVER; timer = 0f
                if (score > hiScore) hiScore = score
            }
            else -> {}
        }
    }

    private fun tick(dt: Float) {
        updateCranes(dt)
        updatePlayer(dt)
        updateLoose(dt)
        if (state == State.PLAYING) checkCrush()
        if (state == State.PLAYING) checkRow()
    }

    // ================= cranes =================

    private fun colX(c: Int) = FIELD_X + c * CELL

    private fun updateCranes(dt: Float) {
        spawnT -= dt
        if (spawnT <= 0f && cranes.size < maxCranes) {
            spawnCrane()
            spawnT = spawnEvery * (0.7f + rng.nextFloat() * 0.6f)
        }
        val it = cranes.iterator()
        while (it.hasNext()) {
            val c = it.next()
            c.x += c.dir * craneSpeed * dt
            if (c.carrying) {
                val tx = colX(c.target).toFloat()
                if ((c.dir > 0 && c.x >= tx) || (c.dir < 0 && c.x <= tx)) {
                    c.carrying = false
                    loose.add(Loose(c.target, CRATE_HANG_R, c.target, 1f))
                }
            }
            if (c.x < -CELL - 2 || c.x > W + 2) it.remove()
        }
    }

    private fun spawnCrane() {
        val pending = cranes.filter { it.carrying }.map { it.target }.toSet()
        val candidates = (0 until COLS).filter { heights[it] < ROWS && it !in pending }
            .ifEmpty { (0 until COLS).filter { heights[it] < ROWS } }
        if (candidates.isEmpty()) return
        val target = candidates[rng.nextInt(candidates.size)]
        val dir = if (rng.nextBoolean()) 1 else -1
        cranes.add(Crane(if (dir > 0) -CELL.toFloat() else W.toFloat(), dir, target))
    }

    // ================= world queries =================

    /** Is cell (c,row) blocked by a settled or loose crate? */
    private fun occupied(c: Int, row: Int): Boolean {
        if (c !in 0 until COLS || row < 0) return true
        if (row < grid[c].size && grid[c][row]) return true
        for (l in loose) if (l.col == c && l.r < row + 1 - EPS && l.r + 1 > row + EPS) return true
        return false
    }

    private fun isIntegral(v: Float) = kotlin.math.abs(v - v.roundToInt()) < EPS

    // ================= player =================

    private fun updatePlayer(dt: Float) {
        // horizontal
        if (stepping) {
            stepT += dt / STEP_TIME
            if (stepT >= 1f) { stepping = false; stepT = 1f }
        }
        if (!stepping) {
            var dir = 0
            val buffered = queuedStep != 0
            if (buffered) { dir = queuedStep; queuedStep = 0 }
            else if (leftHeld != rightHeld) dir = if (leftHeld) -1 else 1
            if (dir != 0) {
                facing = dir
                // A sideways tap during the rise is kept until it can be taken (apex).
                if (!tryStep(dir) && buffered && phase == Phase.RISE) queuedStep = dir
            }
        }

        // jump
        if (queuedJump) {
            queuedJump = false
            if (phase == Phase.GROUND) {
                val fy = py.roundToInt()
                if (!occupied(px, fy + 2)) {
                    phase = Phase.RISE; riseTo = fy + 1f
                    events.addLast(Sfx.JUMP)
                }
            }
        }

        // vertical
        when (phase) {
            Phase.GROUND -> if (py > heights[px] + EPS) phase = Phase.FALL
            Phase.RISE -> {
                val ny = min(riseTo, py + RISE_SPEED * dt)
                val headRow = floor(ny + 2f - EPS).toInt()
                if (headRow < grid[px].size && grid[px][headRow]) {
                    phase = Phase.FALL
                } else {
                    py = ny
                    if (py >= riseTo - EPS) { py = riseTo; phase = Phase.HANG; hangT = HANG_TIME }
                }
            }
            Phase.HANG -> { hangT -= dt; if (hangT <= 0f) phase = Phase.FALL }
            Phase.FALL -> {
                val floorY = heights[px].toFloat()
                val ny = py - PLAYER_FALL * dt
                if (ny <= floorY) { py = floorY; phase = Phase.GROUND } else py = ny
            }
        }
    }

    private fun tryStep(dir: Int): Boolean {
        val nx = px + dir
        if (nx !in 0 until COLS) return false
        val fy = floor(py + EPS).toInt()
        val topRow = floor(py + 2f - EPS).toInt()
        val blocked = (fy..topRow).any { occupied(nx, it) }
        if (!blocked) { beginStep(nx); return true }

        // push: grounded, crate at feet level is the top of its column, space beyond
        if (phase != Phase.GROUND || !isIntegral(py)) return false
        val nx2 = nx + dir
        if (nx2 !in 0 until COLS) return false
        if (!grid[nx][fy] || heights[nx] != fy + 1) return false
        if (occupied(nx, fy + 1) || occupied(nx2, fy)) return false
        grid[nx][fy] = false
        heights[nx]--
        loose.add(Loose(nx2, fy.toFloat(), nx, 0f))
        score += 2
        events.addLast(Sfx.PUSH)
        beginStep(nx)
        return true
    }

    private fun beginStep(nx: Int) {
        stepFrom = px; px = nx; stepT = 0f; stepping = true
        walkFrame = walkFrame xor 1
    }

    // ================= loose crates =================

    private fun updateLoose(dt: Float) {
        if (loose.isEmpty()) return
        loose.sortBy { it.r }
        val landed = ArrayList<Loose>()
        for (l in loose) {
            if (l.slideT < 1f) { l.slideT = min(1f, l.slideT + dt / STEP_TIME); continue }
            val floorR = heights[l.col].toFloat()
            var sup = floorR
            for (o in loose) if (o !== l && o.col == l.col && o.r < l.r) sup = max(sup, o.r + 1f)
            l.r = max(sup, l.r - crateFall * dt)
            if (l.r <= floorR + EPS && sup == floorR) landed.add(l)
        }
        loose.removeAll(landed.toSet())
        for (l in landed) land(l.col)
    }

    private fun land(col: Int) {
        val r = heights[col]
        if (r < grid[col].size) grid[col][r] = true
        heights[col]++
        score += 1
        events.addLast(Sfx.LAND)
        if (heights[col] >= ROWS) die()
    }

    private fun checkCrush() {
        for (l in loose) {
            if (l.col == px && l.slideT >= 1f && l.r < py + 2f - 0.05f && l.r + 1f > py + 0.05f) {
                die(); return
            }
        }
    }

    // ================= rows =================

    private fun checkRow() {
        if ((0 until COLS).all { grid[it][0] }) {
            state = State.CLEARING; timer = 0f
            events.addLast(Sfx.CLEAR)
        }
    }

    private fun finishClear() {
        for (c in 0 until COLS) {
            for (r in 0 until grid[c].size - 1) grid[c][r] = grid[c][r + 1]
            grid[c][grid[c].size - 1] = false
            heights[c]--
        }
        py = max(0f, py - 1f)
        if (phase == Phase.RISE) riseTo -= 1f
        if (phase == Phase.GROUND && py > heights[px] + EPS) phase = Phase.FALL
        lines++
        score += 100 * level
        if (lines % LINES_PER_LEVEL == 0 && level < MAX_LEVEL) {
            level++
            events.addLast(Sfx.LEVEL)
        }
        state = State.PLAYING
        checkRow() // chain clears
    }

    private fun die() {
        if (state == State.DYING) return
        state = State.DYING; timer = 0f
        events.addLast(Sfx.DEATH)
    }

    // ================= drawing =================

    private fun crateY(r: Float) = (FIELD_BOTTOM - (r + 1f) * CELL).roundToInt()

    fun draw(fb: FrameBuffer) {
        fb.clear()
        when (state) {
            State.TITLE -> { drawTitle(fb); return }
            else -> {}
        }
        drawWorld(fb)
        drawHud(fb)
        when (state) {
            State.PAUSED -> {
                fb.panel(24, 40, 52, 16)
                fb.textCentered("PAUSED", 43)
                fb.textCentered("TAP", 49)
            }
            State.GAME_OVER -> {
                fb.panel(12, 30, 76, 34)
                fb.textCentered("GAME OVER", 33)
                fb.textCentered("SCORE ${score.toString().padStart(6, '0')}", 41)
                fb.textCentered("HI    ${hiScore.toString().padStart(6, '0')}", 47)
                if (timer > 0.8f && (blinkT % 1f) < 0.6f) fb.textCentered("TAP TO RETRY", 55)
            }
            else -> {}
        }
    }

    private fun drawHud(fb: FrameBuffer) {
        fb.text(score.toString().padStart(6, '0'), 1, 1)
        val lv = "LV$level"
        fb.text(lv, W - FrameBuffer.textWidth(lv) - 1, 1)
        val ln = "R${lines}"
        fb.text(ln, 50 - FrameBuffer.textWidth(ln) / 2, 1)
    }

    private fun drawWorld(fb: FrameBuffer) {
        // rail and side walls
        fb.hline(0, W - 1, RAIL_Y)
        fb.vline(0, FIELD_TOP, FIELD_BOTTOM - 1)
        fb.vline(W - 1, FIELD_TOP, FIELD_BOTTOM - 1)

        // settled crates (bottom row blinks while clearing)
        val hideBottom = state == State.CLEARING && ((timer * 10).toInt() % 2 == 0)
        for (c in 0 until COLS) for (r in 0 until heights[c]) {
            if (r == 0 && hideBottom) continue
            if (r < ROWS) fb.blit(Art.crate, colX(c), crateY(r.toFloat()))
        }

        // loose crates
        for (l in loose) {
            val t = l.slideT
            val x = (colX(l.fromCol) + (colX(l.col) - colX(l.fromCol)) * t).roundToInt()
            fb.blit(Art.crate, x, crateY(l.r))
        }

        // cranes
        for (c in cranes) {
            val x = c.x.roundToInt()
            fb.blit(Art.crane, x, RAIL_Y + 1)
            if (c.carrying) fb.blit(Art.crate, x, RAIL_Y + 4)
        }

        // player
        val dying = state == State.DYING || state == State.GAME_OVER
        if (state == State.DYING && (timer * 8).toInt() % 2 == 1 && timer < 1f) return
        val sx = if (stepping) colX(stepFrom) + ((colX(px) - colX(stepFrom)) * stepT).roundToInt() else colX(px)
        val sy = (FIELD_BOTTOM - (py + 2f) * CELL).roundToInt()
        val sprite = when {
            dying -> Art.playerSquashed
            phase != Phase.GROUND -> Art.playerJump
            stepping && walkFrame == 1 -> Art.playerWalk
            else -> Art.playerStand
        }
        fb.blit(sprite, sx, sy, flip = facing < 0)
    }

    private fun drawTitle(fb: FrameBuffer) {
        fb.hline(0, W - 1, RAIL_Y)
        // decorative crane carrying a crate across the rail
        val cx = ((blinkT * 20f) % (W + 16)).roundToInt() - 8
        fb.blit(Art.crane, cx, RAIL_Y + 1)
        fb.blit(Art.crate, cx, RAIL_Y + 4)

        fb.textCentered("CRATE RUSH", 26)
        fb.hline(28, 71, 32)
        // little stack scene
        for ((c, h) in listOf(0 to 2, 1 to 1, 10 to 1, 11 to 3)) for (r in 0 until h) {
            fb.blit(Art.crate, colX(c), crateY(r.toFloat()))
        }
        fb.blit(Art.playerStand, colX(5), FIELD_BOTTOM - 16)
        fb.blit(Art.crate, colX(6), crateY(0f))

        if ((blinkT % 1f) < 0.65f) fb.textCentered("TAP TO START", 40)
        fb.textCentered("HI ${hiScore.toString().padStart(6, '0')}", 48)
        fb.textCentered("SIDES:MOVE MID:JUMP", 58)
    }
}
